package de.michiruf.invsync.event;

import de.michiruf.invsync.config.Config;
import de.michiruf.invsync.Logger;
import de.michiruf.invsync.data.AdvancementSyncService;
import de.michiruf.invsync.data.InventorySaveManager;
import de.michiruf.invsync.data.ORMLite;
import de.michiruf.invsync.data.entity.PlayerData;
import de.michiruf.invsync.data.entity.PlayerDataHistory;
import de.michiruf.invsync.mixin_accessor.PlayerAdvancementTrackerAccessor;
import de.michiruf.invsync.scheduler.TickScheduler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.logging.log4j.Level;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * @author Michael Ruf
 * @since 2023-01-05
 */
public class PlayerDataService {

    private static final int MAX_LOAD_RETRIES = 15;
    private static final long SAVE_DEBOUNCE_MS = 2000;

    public static void loadPlayer(ServerPlayerEntity player, ORMLite database, Config config) {
        Logger.log(Level.DEBUG, "Player JOIN event received for " + player.getName().getString());

        // Defensive repair pass: sanitize potentially corrupted advancement timestamps
        // right when a player joins to prevent vanilla save/list serialization crashes.
        if (config.sync.advancements) {
            try {
                var accessor = (PlayerAdvancementTrackerAccessor) player.getAdvancementTracker();
                var sanitized = accessor.readAdvancementData();
                accessor.writeAdvancementData(sanitized);
                Logger.log(Level.DEBUG, "Advancement data sanitized for " + player.getName().getString());
            } catch (Exception e) {
                Logger.log(Level.WARN, "Advancement sanitize pass failed for " + player.getName().getString() + ": " + e.getMessage());
            }
        }

        // Immediately clear inventory and mark as loading
        InventorySaveManager.clearPlayerInventory(player);
        InventorySaveManager.markInventoryLoading(player);

        // Pre-load health synchronously to prevent visual glitches (falling sensation)
        // This happens before the delay so player spawns with correct health
        if (config.sync.health) {
            database.transactionAsync(() -> {
                try {
                    PlayerData playerData = database.playerDataDao.queryForId(player.getUuidAsString());
                    if (playerData != null && playerData.health > 0) {
                        return playerData.health;
                    }
                    return null;
                } catch (Exception e) {
                    Logger.logException(Level.ERROR, e);
                    return null;
                }
            }).thenAcceptAsync(health -> {
                if (health != null) {
                    TickScheduler.schedule(() -> {
                        // Only set health if player is still online
                        if (!player.isRemoved() && player.networkHandler != null) {
                            player.setHealth(health);
                            Logger.log(Level.DEBUG, "Pre-applied health for " + player.getName().getString() + ": " + health);
                        }
                    }, 0);
                }
            }, Runnable::run);
        }

        // Load inventory immediately (no delay)
        // Protection against race conditions is handled by saveInProgress flag
        TickScheduler.schedule(() -> {
            // Validate player is still connected before executing load
            if (player.isRemoved() || player.networkHandler == null) {
                Logger.log(Level.DEBUG, "Player disconnected before load could start: " + player.getName().getString());
                InventorySaveManager.removePlayerFlag(player);
                return;
            }
            loadPlayerAsync(player, database, config, 0);
        }, 0);
    }

    /**
     * Asynchronously loads player data with race condition protection.
     * Checks for saveInProgress flag and waits if necessary.
     */
    private static void loadPlayerAsync(ServerPlayerEntity player, ORMLite database, Config config, int retryCount) {
        database.transactionAsync(() -> {
            try {
                PlayerData playerData = database.playerDataDao.queryForId(player.getUuidAsString());
                if (playerData == null) {
                    Logger.log(Level.INFO, "Player is new, starting with empty inventory: " + player.getName().getString());
                    InventorySaveManager.markInventoryLoaded(player);
                    return null;
                }

                // Check if save is in progress from another server
                if (playerData.saveInProgress) {
                    long timeSinceSaveStarted = Instant.now().toEpochMilli()
                        - playerData.saveInProgressSince.toInstant().toEpochMilli();

                    // If save has been in progress for more than 10 seconds, assume it's stale
                    if (timeSinceSaveStarted > 10000) {
                        Logger.log(Level.WARN, "Save in progress flag is stale (>10s), clearing flag for " + player.getName().getString());
                        playerData.saveInProgress = false;
                        playerData.saveInProgressSince = null;
                        database.playerDataDao.update(playerData);
                    } else {
                        // Wait for save to complete
                        Logger.log(Level.INFO, "Save in progress detected, waiting for completion: " + player.getName().getString());
                        return playerData; // Will retry
                    }
                }

                // Check initial sync conditions
                if (config.initialSync.initialSyncOverwriteEnabled &&
                        !Arrays.asList(playerData.initializedServers).contains(config.initialSync.initialSyncServerName)) {
                    Logger.log(Level.INFO, "Player data will be overwritten (initial sync): " + player.getName().getString());
                    InventorySaveManager.markInventoryLoaded(player);
                    return null;
                }

                return playerData;
            } catch (Exception e) {
                Logger.logException(Level.ERROR, e);
                throw new RuntimeException("Failed to load player data", e);
            }
        }).thenAcceptAsync(playerData -> {
            // Back on game thread - apply inventory
            TickScheduler.schedule(() -> {
                // Validate player is still online before applying inventory
                if (player.isRemoved() || player.networkHandler == null) {
                    Logger.log(Level.WARN, "Player disconnected before inventory could be loaded: " + player.getName().getString());
                    InventorySaveManager.removePlayerFlag(player);
                    return;
                }

                if (playerData != null && playerData.saveInProgress) {
                    if (retryCount >= MAX_LOAD_RETRIES) {
                        Logger.log(Level.ERROR, "Load retries exceeded for " + player.getName().getString() + ", aborting to prevent race/dupe state");
                        InventorySaveManager.disableInventorySave(player);
                        InventorySaveManager.removePlayerFlag(player);
                        if (player.networkHandler != null) {
                            player.networkHandler.disconnect(Text.of("Inventory sync busy, please reconnect in a moment"));
                        }
                        return;
                    }

                    // Need to retry - schedule another attempt
                    Logger.log(Level.DEBUG, "Retrying load for " + player.getName().getString() + " (attempt " + (retryCount + 1) + "/" + MAX_LOAD_RETRIES + ")");
                    TickScheduler.schedule(() -> loadPlayerAsync(player, database, config, retryCount + 1), 20); // Retry after 1 second
                } else if (playerData != null) {
                    // Apply the inventory on game thread
                    try {
                        InvSyncEvents.FETCH_PLAYER_DATA.invoker().handle(player, playerData);

                        // OPTIMIZATION: Load advancements separately if version changed
                        // This prevents loading massive advancement JSON on every server hop
                        if (config.sync.advancements && AdvancementSyncService.needsAdvancementLoad(player, playerData, database)) {
                            AdvancementSyncService.loadAdvancements(player, playerData, database);
                        }

                        InventorySaveManager.markInventoryLoaded(player);
                        Logger.log(Level.INFO, "Successfully loaded inventory for " + player.getName().getString());
                    } catch (Exception e) {
                        Logger.logException(Level.ERROR, e);
                        // Error is handled in InvSyncEvents (kicks player)
                    }
                } else {
                    // New player or initial sync - already marked as loaded
                    InventorySaveManager.markInventoryLoaded(player);
                    Logger.log(Level.DEBUG, "No inventory to load for " + player.getName().getString());
                }
            }, 0);
        }, Runnable::run).exceptionally(ex -> {
            // Database error - kick player with message
            Logger.logException(Level.ERROR, ex);
            TickScheduler.schedule(() -> {
                InventorySaveManager.disableInventorySave(player);
                InventorySaveManager.removePlayerFlag(player); // Clean up flags to prevent memory leak

                // Try to send player to fallback server (for Velocity) to avoid reconnect loop
                if (config.fallbackServer != null && !config.fallbackServer.isEmpty()) {
                    Logger.log(Level.INFO, "Sending " + player.getName().getString() + " to fallback server: " + config.fallbackServer);
                    player.server.getCommandManager().executeWithPrefix(
                        player.server.getCommandSource(),
                        "execute as " + player.getName().getString() + " run server " + config.fallbackServer
                    );
                    // Small delay before disconnect to allow command to execute
                    TickScheduler.schedule(() -> {
                        player.networkHandler.disconnect(Text.of("Inventory failed to load, sent to " + config.fallbackServer));
                    }, 10);
                } else {
                    player.networkHandler.disconnect(Text.of("Inventory failed to load, please try again"));
                }
            }, 0);
            return null;
        });
    }

    public static void savePlayer(ServerPlayerEntity player, ORMLite database, Config config) {
        Logger.log(Level.DEBUG, "Player DISCONNECT event received for " + player.getName().getString());

        // Debounce rapid duplicate disconnect events to reduce dupe race windows.
        if (InventorySaveManager.shouldDebounceSave(player, SAVE_DEBOUNCE_MS)) {
            Logger.log(Level.DEBUG, "Debounced duplicate save for " + player.getName().getString());
            return;
        }

        // Ensure only one save pipeline can run per player at once.
        if (!InventorySaveManager.beginSave(player)) {
            Logger.log(Level.WARN, "Save already in progress for " + player.getName().getString() + ", skipping duplicate save call");
            return;
        }

        // Check if inventory is still loading - if so, don't save!
        if (InventorySaveManager.isInventoryLoading(player)) {
            Logger.log(Level.WARN, "Player disconnected while inventory was loading, skipping save to prevent data loss: " + player.getName().getString());
            InventorySaveManager.removePlayerFlag(player);
            InventorySaveManager.endSave(player);
            return;
        }

        // Check if save is disabled (load failed)
        if (!InventorySaveManager.shouldSaveInventory(player)) {
            Logger.log(Level.INFO, "Save disabled for player (load failed): " + player.getName().getString());
            InventorySaveManager.removePlayerFlag(player);
            InventorySaveManager.endSave(player);
            return;
        }

        // First, mark save as in progress
        database.transactionAsync(() -> {
            try {
                PlayerData playerData = database.playerDataDao.queryForId(player.getUuidAsString());
                if (playerData == null) {
                    playerData = new PlayerData(player.getUuid());
                }
                playerData.saveInProgress = true;
                playerData.saveInProgressSince = java.sql.Date.from(Instant.now());
                database.playerDataDao.createOrUpdate(playerData);
                Logger.log(Level.DEBUG, "Marked save in progress for " + player.getName().getString());
                return null;
            } catch (Exception e) {
                Logger.logException(Level.ERROR, e);
                throw new RuntimeException("Failed to mark save in progress", e);
            }
        }).thenCompose(v -> {
            // Now perform the actual save
            return database.transactionAsync(() -> {
                try {
                    PlayerData playerData = database.playerDataDao.queryForId(player.getUuidAsString());
                    if (playerData == null) {
                        playerData = new PlayerData(player.getUuid());
                    }

                    playerData.playerUsername = player.getDisplayName().getString();

                    InvSyncEvents.SAVE_PLAYER_DATA.invoker().handle(player, playerData);

                    // OPTIMIZATION: Save advancements to separate table with compression
                    // This is done before prepareSave so the version is updated
                    if (config.sync.advancements) {
                        // Defensive sanitize pass again at save-time to avoid serializing corrupt dates.
                        try {
                            var accessor = (PlayerAdvancementTrackerAccessor) player.getAdvancementTracker();
                            var sanitized = accessor.readAdvancementData();
                            accessor.writeAdvancementData(sanitized);
                        } catch (Exception e) {
                            Logger.log(Level.WARN, "Advancement sanitize before save failed for " + player.getName().getString() + ": " + e.getMessage());
                        }

                        AdvancementSyncService.saveAdvancements(player, playerData, database);
                        // Clear the DEPRECATED advancements field to save database space
                        // Data is now in player_advancements table with compression
                        playerData.advancements = new com.google.gson.JsonObject();
                    }

                    playerData.prepareSave(config);

                    // Clear the saveInProgress flag
                    playerData.saveInProgress = false;
                    playerData.saveInProgressSince = null;

                    database.playerDataDao.createOrUpdate(playerData);

                    Logger.log(Level.INFO, "Successfully saved inventory for " + player.getName().getString());
                    return playerData;
                } catch (Exception e) {
                    Logger.logException(Level.ERROR, e);
                    throw new RuntimeException("Failed to save player data", e);
                }
            });
        }).thenComposeAsync(playerData -> {
            // Save history in a separate async transaction
            return database.transactionAsync(() -> {
                try {
                    Date insertDate = java.sql.Date.from(Instant.now());
                    PlayerDataHistory history = new PlayerDataHistory(player.getUuid(), insertDate);

                    if (playerData != null) {
                        history.creationDate = playerData.date;
                        history.playerUsername = playerData.playerUsername;
                        // NOTE: Advancements no longer copied to history - they have their own history table
                        // history.advancements is left as empty JsonObject (default value)
                        history.effects = playerData.effects;
                        history.health = playerData.health;
                        history.enderChest = playerData.enderChest;
                        history.inventory = playerData.inventory;
                        history.hunger = playerData.hunger;
                        history.initializedServers = playerData.initializedServers;
                        history.playerUuid = playerData.playerUuid;
                        history.xp = playerData.xp;
                        history.xpProgress = playerData.xpProgress;
                        history.trinkets = playerData.trinkets;
                        history.score = playerData.score;
                        history.selectedSlot = playerData.selectedSlot;
                        history.saveInProgress = playerData.saveInProgress;
                        history.saveInProgressSince = playerData.saveInProgressSince;
                    }

                    history.prepareSave(config);
                    database.playerDataHistoryDao.create(history);

                    Logger.log(Level.DEBUG, "Saved history for " + player.getName().getString());
                    return null;
                } catch (Exception e) {
                    Logger.logException(Level.ERROR, e);
                    // Don't fail the whole save if history fails
                    return null;
                }
            });
        }, Runnable::run).thenRun(() -> {
            InventorySaveManager.markSaveTimestamp(player);
            InventorySaveManager.endSave(player);
        }).exceptionally(ex -> {
            // If save fails, try to clear the saveInProgress flag
            Logger.logException(Level.ERROR, ex);
            Logger.log(Level.ERROR, "Failed to save player data for " + player.getName().getString() + ", attempting to clear saveInProgress flag");
            InventorySaveManager.endSave(player);

            database.transactionAsync(() -> {
                try {
                    PlayerData playerData = database.playerDataDao.queryForId(player.getUuidAsString());
                    if (playerData != null) {
                        playerData.saveInProgress = false;
                        playerData.saveInProgressSince = null;
                        database.playerDataDao.update(playerData);
                    }
                    return null;
                } catch (Exception e) {
                    Logger.logException(Level.ERROR, e);
                    return null;
                }
            });
            return null;
        });
    }
}
