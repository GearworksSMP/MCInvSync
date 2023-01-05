package mrnavastar.invsync.event;

import mrnavastar.invsync.Config;
import mrnavastar.invsync.Logger;
import mrnavastar.invsync.data.ORMLite;
import mrnavastar.invsync.data.entity.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.Level;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * @author Michael Ruf
 * @since 2023-01-05
 */
public class PlayerDataService {

    public static void loadPlayer(ServerPlayerEntity player, ORMLite database, Config config) {
        Logger.log(Level.DEBUG, "Player JOIN event received");

        // Unfortunately, fabric does not have a scheduler, so we just go with the good old
        // plain java thread and sleep
        new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(1);
                loadPlayerImpl(player, database, config);
            } catch (InterruptedException e) {
                Logger.logException(Level.ERROR, e);
            }
        }).start();
    }

    private static void loadPlayerImpl(ServerPlayerEntity player, ORMLite database, Config config) {
        database.transaction(() -> {
            try {
                var playerData = database.playerDataDao.queryForId(player.getUuidAsString());
                if (playerData == null) {
                    Logger.log(Level.INFO, "Player JOIN event not processed because player is new");
                    return;
                }

                // If initial sync enabled, sync mode OVERWRITE and the server name is not contained in the
                // initial servers list, we do not need to process the data here, because the data should get
                // lost by the overwrite mechanism anyway
                if (config.INITIAL_SYNC_OVERWRITE_ENABLED &&
                        !Arrays.asList(playerData.initializedServers).contains(config.INITIAL_SYNC_SERVER_NAME)) {
                    Logger.log(Level.INFO, "Player JOIN event not processed because data shell be overwritten");
                    return;
                }

                InvSyncEvents.FETCH_PLAYER_DATA.invoker().handle(player, playerData);
                Logger.log(Level.DEBUG, "Player JOIN event processed");
            } catch (Exception e) {
                Logger.logException(Level.ERROR, e);
            }
        }, e -> Logger.logException(Level.ERROR, e));
    }

    public static void savePlayer(ServerPlayerEntity player, ORMLite database, Config config) {
        Logger.log(Level.DEBUG, "Player DISCONNECT event received");

        database.transaction(() -> {
            try {
                PlayerData playerData = database.playerDataDao.queryForId(player.getUuidAsString());
                if (playerData == null)
                    playerData = new PlayerData(player.getUuid());

                InvSyncEvents.SAVE_PLAYER_DATA.invoker().handle(player, playerData);
                playerData.prepareSave(config);
                database.playerDataDao.createOrUpdate(playerData);

                Logger.log(Level.DEBUG, "Player DISCONNECT event processed");
            } catch (Exception e) {
                Logger.logException(Level.ERROR, e);
            }
        }, e -> Logger.logException(Level.ERROR, e));
    }
}
