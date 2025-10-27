package de.michiruf.invsync.event;

import de.michiruf.invsync.Logger;
import de.michiruf.invsync.data.InventorySaveManager;
import de.michiruf.invsync.data.entity.PlayerData;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.logging.log4j.Level;

public class InvSyncEvents {

    public static final Event<PlayerDataHandler> FETCH_PLAYER_DATA = EventFactory.createArrayBacked(PlayerDataHandler.class, callbacks -> (player, playerData) -> {
        if (playerData == null) {
            Logger.log(Level.WARN, "PlayerData is null for " + player.getName().getString());
            return;
        }

        for (var callback : callbacks) {
            try {
                callback.handle(player, playerData);
            } catch (Exception e) {
                Logger.logException(Level.ERROR, e);
                Logger.log(Level.ERROR, "Failed to load inventory for " + player.getName().getString() + ": " + e.getMessage());
                InventorySaveManager.disableInventorySave(player);
                InventorySaveManager.markInventoryLoaded(player); // Stop showing as loading
                player.networkHandler.disconnect(Text.of("Inventory failed to load, please try again"));
                throw e; // Re-throw so the async handler knows it failed
            }
        }
        // Only remove flag if all callbacks succeeded
        InventorySaveManager.removePlayerFlag(player);
    });

    public static final Event<PlayerDataHandler> SAVE_PLAYER_DATA = EventFactory.createArrayBacked(PlayerDataHandler.class, callbacks -> (player, playerData) -> {
        for (var callback : callbacks) {
            try {
                if (!InventorySaveManager.shouldSaveInventory(player)) {
                    // Skip saving inventory
                    return;
                }
                callback.handle(player, playerData);
            } catch (Exception e) {
                Logger.logException(Level.ERROR, e);
            }
        InventorySaveManager.removePlayerFlag(player);
            }
    });

    @FunctionalInterface
    public interface PlayerDataHandler {

        void handle(ServerPlayerEntity player, PlayerData playerData) throws Exception;
    }
}
