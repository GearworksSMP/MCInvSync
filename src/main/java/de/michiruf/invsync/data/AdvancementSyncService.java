package de.michiruf.invsync.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.michiruf.invsync.Logger;
import de.michiruf.invsync.data.entity.PlayerAdvancements;
import de.michiruf.invsync.data.entity.PlayerAdvancementsHistory;
import de.michiruf.invsync.data.entity.PlayerData;
import de.michiruf.invsync.mixin_accessor.PlayerAdvancementTrackerAccessor;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.Level;

public class AdvancementSyncService {

    public static boolean needsAdvancementLoad(ServerPlayerEntity player, PlayerData playerData, ORMLite database) {
        try {
            PlayerAdvancements stored = database.playerAdvancementsDao.queryForId(player.getUuidAsString());
            if (stored == null) return false;
            return stored.version > playerData.advancementVersion;
        } catch (Exception e) {
            Logger.logException(Level.ERROR, e);
            return false;
        }
    }

    public static void loadAdvancements(ServerPlayerEntity player, PlayerData playerData, ORMLite database) {
        try {
            PlayerAdvancements stored = database.playerAdvancementsDao.queryForId(player.getUuidAsString());
            if (stored == null || stored.advancements == null) return;

            JsonElement toLoad = stored.advancements;
            if (toLoad.isJsonNull()) return;

            ((PlayerAdvancementTrackerAccessor) player.getAdvancementTracker()).writeAdvancementData(toLoad);
            playerData.advancementVersion = stored.version;
            Logger.log(Level.DEBUG, "Loaded advancements version " + stored.version + " for " + player.getName().getString());
        } catch (Exception e) {
            Logger.logException(Level.ERROR, e);
        }
    }

    public static void saveAdvancements(ServerPlayerEntity player, PlayerData playerData, ORMLite database) {
        try {
            JsonElement current = ((PlayerAdvancementTrackerAccessor) player.getAdvancementTracker()).readAdvancementData();
            if (current == null || current.isJsonNull()) {
                current = new JsonObject();
            }

            PlayerAdvancements stored = database.playerAdvancementsDao.queryForId(player.getUuidAsString());
            if (stored == null) {
                stored = new PlayerAdvancements(player.getUuid());
            }

            stored.advancements = current;
            stored.touchAndBumpVersion();
            database.playerAdvancementsDao.createOrUpdate(stored);

            playerData.advancementVersion = stored.version;

            PlayerAdvancementsHistory history = new PlayerAdvancementsHistory(player.getUuid(), stored.version, current);
            database.playerAdvancementsHistoryDao.create(history);
        } catch (Exception e) {
            Logger.logException(Level.ERROR, e);
        }
    }
}
