package de.michiruf.invsync.event;

import com.google.gson.JsonElement;
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

    private static void audit(ServerPlayerEntity player, String event, String detail) {
        String username = player != null ? player.getName().getString() : "unknown";
        String uuid = player != null ? player.getUuidAsString() : "unknown";
        Logger.log(Level.INFO, "[AUDIT] event=" + event + " user=" + username + " uuid=" + uuid + " " + detail);
    }

    public static void loadPlayer(ServerPlayerEntity player, ORMLite database, Config config) {
        Logger.log(Level.DEBUG, "Player JOIN event received for " + player.getName().getString());
        audit(player, "join_received", "phase=load_start");

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
                        audit(player, "load_retry_exhausted", "retries=" + retryCount);
                        InventorySaveManager.disableInventorySave(player);
                        InventorySaveManager.removePlayerFlag(player);
                        if (player.networkHandler != null) {
                            player.networkHandler.disconnect(Text.of("Inventory sync busy, please reconnect in a moment"));
                        }
                        return;
                    }

                    // Need to retry - schedule another attempt
                    Logger.log(Level.DEBUG, "Retrying load for " + player.getName().getString() + " (attempt " + (retryCount + 1) + "/" + MAX_LOAD_RETRIES + ")");
                    audit(player, "load_retry", "attempt=" + (retryCount + 1));
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
                        audit(player, "load_success", "phase=apply_inventory");
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
        savePlayer(player, database, config, false);
    }

    public static void savePlayer(ServerPlayerEntity player, ORMLite database, Config config, boolean isPeriodic) {
        Logger.log(Level.DEBUG, (isPeriodic ? "Periodic SAVE" : "Player DISCONNECT") + " event received for " + player.getName().getString());

        // Debounce rapid duplicate disconnect events to reduce dupe race windows.
        if (InventorySaveManager.shouldDebounceSave(player, SAVE_DEBOUNCE_MS)) {
            Logger.log(Level.DEBUG, "Debounced duplicate save for " + player.getName().getString());
            audit(player, "save_debounced", "windowMs=" + SAVE_DEBOUNCE_MS);
            return;
        }

        // Ensure only one save pipeline can run per player at once.
        if (!InventorySaveManager.beginSave(player)) {
            Logger.log(Level.WARN, "Save already in progress for " + player.getName().getString() + ", skipping duplicate save call");
            audit(player, "save_lock_rejected", "reason=lock_held");
            return;
        }
        audit(player, "save_lock_acquired", "phase=disconnect_save");

        // Check if inventory is still loading - if so, don't save!
        if (InventorySaveManager.isInventoryLoading(player)) {
            Logger.log(Level.WARN, "Player disconnected while inventory was loading, skipping save to prevent data loss: " + player.getName().getString());
            audit(player, "save_skipped", "reason=inventory_loading");
            InventorySaveManager.removePlayerFlag(player);
            InventorySaveManager.endSave(player);
            return;
        }

        // Check if save is disabled (load failed)
        if (!InventorySaveManager.shouldSaveInventory(player)) {
            Logger.log(Level.INFO, "Save disabled for player (load failed): " + player.getName().getString());
            audit(player, "save_skipped", "reason=save_disabled");
            InventorySaveManager.removePlayerFlag(player);
            InventorySaveManager.endSave(player);
            return;
        }

        // ================================================================
        // CRITICAL: Capture all player entity state SYNCHRONOUSLY before
        // going async. Player objects (inventory, advancement tracker, etc.)
        // must only be accessed from the game thread. Accessing them from
        // the async DB worker thread causes ConcurrentModificationException
        // server crashes because vanilla concurrently iterates the same
        // data structures (e.g., advancement progress LinkedHashMap)
        // during its own save/disconnect cycle on the server thread.
        // ================================================================

        // Capture player identity (immutable, but grab now for clarity)
        final String playerUuid = player.getUuidAsString();
        final UUID playerUuidObj = player.getUuid();
        final String playerName = player.getName().getString();
        final String playerUsername = player.getDisplayName().getString();

        // Capture player data snapshot via event handlers (reads inventory, health, etc.)
        PlayerData snapshot = new PlayerData(playerUuidObj);
        snapshot.playerUsername = playerUsername;
        try {
            InvSyncEvents.SAVE_PLAYER_DATA.invoker().handle(player, snapshot);
        } catch (Exception e) {
            Logger.logException(Level.ERROR, e);
            Logger.log(Level.ERROR, "Failed to capture player data for " + playerName);
            InventorySaveManager.endSave(player);
            audit(player, "save_failed", "reason=snapshot_capture_error");
            return;
        }

        // Capture advancement data snapshot
        JsonElement advancementSnapshot = null;
        if (config.sync.advancements) {
            try {
                var accessor = (PlayerAdvancementTrackerAccessor) player.getAdvancementTracker();
                var sanitized = accessor.readAdvancementData();
                accessor.writeAdvancementData(sanitized);
                advancementSnapshot = sanitized;
            } catch (Exception e) {
                Logger.log(Level.WARN, "Advancement sanitize before save failed for " + playerName + ": " + e.getMessage());
            }
        }

        // All player entity reads are done. Only DB operations below.
        final JsonElement finalAdvancementSnapshot = advancementSnapshot;

        // Compute hash for change detection
        final long snapshotHash = computeSnapshotHash(snapshot, finalAdvancementSnapshot);

        // For periodic saves, skip if data hasn't changed since last save
        if (isPeriodic) {
            Long previousHash = InventorySaveManager.getLastSaveHash(player);
            if (previousHash != null && previousHash == snapshotHash) {
                Logger.log(Level.DEBUG, "Skipping periodic save for " + playerName + " (data unchanged)");
                audit(player, "save_skipped", "reason=unchanged_periodic");
                InventorySaveManager.endSave(player);
                return;
            }
        }

        // First, mark save as in progress
        database.transactionAsync(() -> {
            try {
                PlayerData playerData = database.playerDataDao.queryForId(playerUuid);
                if (playerData == null) {
                    playerData = new PlayerData(playerUuidObj);
                }
                playerData.saveInProgress = true;
                playerData.saveInProgressSince = java.sql.Date.from(Instant.now());
                database.playerDataDao.createOrUpdate(playerData);
                Logger.log(Level.DEBUG, "Marked save in progress for " + playerName);
                return null;
            } catch (Exception e) {
                Logger.logException(Level.ERROR, e);
                throw new RuntimeException("Failed to mark save in progress", e);
            }
        }).thenCompose(v -> {
            // Write the pre-captured snapshot to DB (no player entity access here)
            return database.transactionAsync(() -> {
                try {
                    PlayerData playerData = database.playerDataDao.queryForId(playerUuid);
                    if (playerData == null) {
                        playerData = new PlayerData(playerUuidObj);
                    }

                    // Apply pre-captured snapshot data to DB record
                    playerData.playerUsername = snapshot.playerUsername;
                    playerData.inventory = snapshot.inventory;
                    playerData.selectedSlot = snapshot.selectedSlot;
                    playerData.enderChest = snapshot.enderChest;
                    playerData.hunger = snapshot.hunger;
                    playerData.health = snapshot.health;
                    playerData.score = snapshot.score;
                    playerData.xp = snapshot.xp;
                    playerData.xpProgress = snapshot.xpProgress;
                    playerData.effects = snapshot.effects;
                    playerData.trinkets = snapshot.trinkets;

                    // Save advancements from pre-captured snapshot (no player entity access)
                    if (config.sync.advancements) {
                        AdvancementSyncService.saveAdvancements(
                                playerUuid, playerUuidObj, playerData, database, finalAdvancementSnapshot);
                        // Clear the DEPRECATED advancements field to save database space
                        // Data is now in player_advancements table with compression
                        playerData.advancements = new com.google.gson.JsonObject();
                    }

                    playerData.prepareSave(config);

                    // Clear the saveInProgress flag
                    playerData.saveInProgress = false;
                    playerData.saveInProgressSince = null;

                    database.playerDataDao.createOrUpdate(playerData);

                    Logger.log(Level.INFO, "Successfully saved inventory for " + playerName);
                    audit(player, "save_success", "phase=db_write_complete");
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
                    PlayerDataHistory history = new PlayerDataHistory(playerUuidObj, insertDate);

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

                    Logger.log(Level.DEBUG, "Saved history for " + playerName);
                    return null;
                } catch (Exception e) {
                    Logger.logException(Level.ERROR, e);
                    // Don't fail the whole save if history fails
                    return null;
                }
            });
        }, Runnable::run).thenRun(() -> {
            InventorySaveManager.setLastSaveHash(player, snapshotHash);
            InventorySaveManager.markSaveTimestamp(player);
            InventorySaveManager.endSave(player);
            audit(player, "save_lock_released", "reason=success");
        }).exceptionally(ex -> {
            // If save fails, try to clear the saveInProgress flag
            Logger.logException(Level.ERROR, ex);
            Logger.log(Level.ERROR, "Failed to save player data for " + playerName + ", attempting to clear saveInProgress flag");
            InventorySaveManager.endSave(player);
            audit(player, "save_lock_released", "reason=exception");

            database.transactionAsync(() -> {
                try {
                    PlayerData playerData = database.playerDataDao.queryForId(playerUuid);
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

    private static long computeSnapshotHash(PlayerData snapshot, JsonElement advancementSnapshot) {
        long hash = 1;
        hash = 31 * hash + (snapshot.inventory != null ? snapshot.inventory.toString().hashCode() : 0);
        hash = 31 * hash + snapshot.selectedSlot;
        hash = 31 * hash + (snapshot.enderChest != null ? snapshot.enderChest.toString().hashCode() : 0);
        hash = 31 * hash + (snapshot.hunger != null ? snapshot.hunger.toString().hashCode() : 0);
        hash = 31 * hash + Float.floatToIntBits(snapshot.health);
        hash = 31 * hash + snapshot.score;
        hash = 31 * hash + snapshot.xp;
        hash = 31 * hash + Float.floatToIntBits(snapshot.xpProgress);
        hash = 31 * hash + (snapshot.effects != null ? snapshot.effects.toString().hashCode() : 0);
        hash = 31 * hash + (snapshot.trinkets != null ? snapshot.trinkets.toString().hashCode() : 0);
        hash = 31 * hash + (advancementSnapshot != null ? advancementSnapshot.toString().hashCode() : 0);
        return hash;
    }
}
