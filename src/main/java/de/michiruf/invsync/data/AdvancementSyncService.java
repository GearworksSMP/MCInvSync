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

import java.util.UUID;

public class AdvancementSyncService {

    public static boolean needsAdvancementLoad(ServerPlayerEntity player, PlayerData playerData, ORMLite database) {
        try {
            PlayerAdvancements stored = database.playerAdvancementsDao.queryForId(player.getUuidAsString());
            if (stored == null) return false;
            // Always load from DB when data exists. The previous "stored.version >
            // playerData.advancementVersion" optimization was broken: on save, both
            // values are set equal, so the condition was never true on the next join.
            // This caused advancements to be lost after server crashes (vanilla's
            // local file not saved) and on cross-server transitions.
            return stored.advancements != null && !stored.advancements.isJsonNull();
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

    /**
     * Saves pre-captured advancement data to the database. Does NOT access the player entity.
     * Advancement data must be captured on the game thread before calling this method from
     * an async context, to avoid ConcurrentModificationException with vanilla's advancement
     * tracker which uses a non-thread-safe LinkedHashMap.
     *
     * @param playerUuid    Player UUID string for DB lookup
     * @param playerUuidObj Player UUID object for creating new records
     * @param playerData    PlayerData record to update advancementVersion on
     * @param database      Database connection
     * @param advancementData Pre-captured advancement JSON (from readAdvancementData on game thread)
     */
    public static void saveAdvancements(String playerUuid, UUID playerUuidObj,
                                        PlayerData playerData, ORMLite database,
                                        JsonElement advancementData) {
        try {
            JsonElement current = advancementData;
            if (current == null || current.isJsonNull()) {
                current = new JsonObject();
            }

            PlayerAdvancements stored = database.playerAdvancementsDao.queryForId(playerUuid);
            if (stored == null) {
                stored = new PlayerAdvancements(playerUuidObj);
            }

            stored.advancements = current;
            stored.touchAndBumpVersion();
            database.playerAdvancementsDao.createOrUpdate(stored);

            playerData.advancementVersion = stored.version;

            PlayerAdvancementsHistory history = new PlayerAdvancementsHistory(playerUuidObj, stored.version, current);
            database.playerAdvancementsHistoryDao.create(history);
        } catch (Exception e) {
            Logger.logException(Level.ERROR, e);
        }
    }
}
