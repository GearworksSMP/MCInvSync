package de.michiruf.invsync.data;

import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InventorySaveManager {
    // Map to track players who should not have their inventory saved
    private static final Map<UUID, Boolean> disableInventorySaveMap = new ConcurrentHashMap<>();

    // Map to track players whose inventory is being loaded (should be kept empty)
    private static final Map<UUID, Boolean> inventoryLoadingMap = new ConcurrentHashMap<>();

    // Map to track players currently being saved to avoid concurrent double-saves/dupes
    private static final Map<UUID, Boolean> saveInProgressMap = new ConcurrentHashMap<>();

    // Last successful save timestamp (ms) for debounce against rapid duplicate disconnect events
    private static final Map<UUID, Long> lastSaveTimestampMap = new ConcurrentHashMap<>();

    // Hash of last successfully saved snapshot, used to skip unchanged periodic saves
    private static final Map<UUID, Long> lastSaveHashMap = new ConcurrentHashMap<>();

    /**
     * Disables inventory saving for a specific player.
     *
     * @param player The player for whom inventory saving should be disabled.
     */
    public static void disableInventorySave(ServerPlayerEntity player) {
        disableInventorySaveMap.put(player.getUuid(), true);
    }

    /**
     * Checks whether inventory should be saved for a specific player.
     *
     * @param player The player to check.
     * @return True if inventory should be saved, false otherwise.
     */
    public static boolean shouldSaveInventory(ServerPlayerEntity player) {
        return !disableInventorySaveMap.getOrDefault(player.getUuid(), false);
    }

    /**
     * Cleans up the flag for a player when they disconnect.
     *
     * @param player The player who has disconnected.
     */
    public static void removePlayerFlag(ServerPlayerEntity player) {
        disableInventorySaveMap.remove(player.getUuid());
        inventoryLoadingMap.remove(player.getUuid());
        saveInProgressMap.remove(player.getUuid());
        lastSaveTimestampMap.remove(player.getUuid());
        lastSaveHashMap.remove(player.getUuid());
    }

    /**
     * Tries to acquire per-player save lock.
     *
     * @return true if lock acquired, false if save already running.
     */
    public static boolean beginSave(ServerPlayerEntity player) {
        return saveInProgressMap.putIfAbsent(player.getUuid(), true) == null;
    }

    public static void endSave(ServerPlayerEntity player) {
        saveInProgressMap.remove(player.getUuid());
    }

    /**
     * Debounce repeated save attempts in a very short window.
     */
    public static boolean shouldDebounceSave(ServerPlayerEntity player, long windowMs) {
        Long last = lastSaveTimestampMap.get(player.getUuid());
        long now = System.currentTimeMillis();
        return last != null && (now - last) < windowMs;
    }

    public static void markSaveTimestamp(ServerPlayerEntity player) {
        lastSaveTimestampMap.put(player.getUuid(), System.currentTimeMillis());
    }

    public static Long getLastSaveHash(ServerPlayerEntity player) {
        return lastSaveHashMap.get(player.getUuid());
    }

    public static void setLastSaveHash(ServerPlayerEntity player, long hash) {
        lastSaveHashMap.put(player.getUuid(), hash);
    }

    /**
     * Marks a player's inventory as currently loading.
     * While loading, their inventory should remain empty.
     *
     * @param player The player whose inventory is being loaded.
     */
    public static void markInventoryLoading(ServerPlayerEntity player) {
        inventoryLoadingMap.put(player.getUuid(), true);
    }

    /**
     * Marks a player's inventory as finished loading.
     *
     * @param player The player whose inventory has been loaded.
     */
    public static void markInventoryLoaded(ServerPlayerEntity player) {
        inventoryLoadingMap.remove(player.getUuid());
    }

    /**
     * Checks if a player's inventory is currently being loaded.
     *
     * @param player The player to check.
     * @return True if inventory is currently loading, false otherwise.
     */
    public static boolean isInventoryLoading(ServerPlayerEntity player) {
        return inventoryLoadingMap.getOrDefault(player.getUuid(), false);
    }

    /**
     * Clears a player's entire inventory (main inventory, armor, offhand, ender chest, trinkets/curios).
     * Used to ensure player starts with empty inventory while database load is in progress.
     *
     * @param player The player whose inventory should be cleared.
     */
    public static void clearPlayerInventory(ServerPlayerEntity player) {
        // Clear main inventory (includes armor and offhand)
        player.getInventory().clear();

        // Clear ender chest
        player.getEnderChestInventory().clear();

        // Clear trinkets/curios to prevent loading from world data
        TrinketsApi.getTrinketComponent(player).ifPresent(trinkets -> {
            trinkets.forEach((ref, stack) -> {
                ref.inventory().setStack(ref.index(), net.minecraft.item.ItemStack.EMPTY);
            });
        });
    }
}
