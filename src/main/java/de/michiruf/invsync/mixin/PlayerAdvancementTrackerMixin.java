package de.michiruf.invsync.mixin;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import de.michiruf.invsync.InvSync;
import de.michiruf.invsync.Logger;
import de.michiruf.invsync.mixin_accessor.PlayerAdvancementTrackerAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.server.ServerAdvancementLoader;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.Map;

@Mixin(PlayerAdvancementTracker.class)
public abstract class PlayerAdvancementTrackerMixin implements PlayerAdvancementTrackerAccessor {

    private static final String DATA_VERSION_PROPERTY = "DataVersion";

    @Shadow
    @Final
    private Map<Advancement, AdvancementProgress> progress;

    @Shadow
    protected abstract void initProgress(Advancement advancement, AdvancementProgress progress);

    @Shadow
    protected abstract void beginTrackingAllAdvancements(ServerAdvancementLoader advancementLoader);

    @Shadow
    @Final
    private static Gson GSON;

    @Shadow
    @Final
    private static TypeToken<Map<Identifier, AdvancementProgress>> JSON_TYPE;

    @Shadow
    @Final
    private DataFixer dataFixer;

    @Override
    public synchronized void writeAdvancementData(JsonElement advancementData) {
        // NOTE If everything is done, that is normally done when handling advancements, a
        // ConcurrentModificationException occurs
        // But since we want just the state to be up-to-date, it is totally okay not to do everything

        // Therefore, this should not be needed to do so?
        //clearCriteria();
        //advancementToProgress.clear();
        //visibleAdvancements.clear();
        //visibilityUpdates.clear();
        //progressUpdates.clear();
        //dirty = true;
        //currentDisplayTab = null;

        Dynamic<JsonElement> dynamic = new Dynamic<>(JsonOps.INSTANCE, GSON.fromJson(advancementData, JsonElement.class));
        // Code got from PlayerAdvancementTracker#load(ServerAdvancementLoader)
        if (dynamic.get(DATA_VERSION_PROPERTY).asNumber().result().isEmpty()) {
            dynamic = dynamic.set(DATA_VERSION_PROPERTY, dynamic.createInt(1343));
        }
//        dynamic = dataFixer.update(
//                DataFixTypes.ADVANCEMENTS.getTypeReference(),
//                dynamic,
//                dynamic.get(DATA_VERSION_PROPERTY).asInt(0),
//                SharedConstants.getGameVersion().getWorldVersion());
        dynamic = dynamic.remove(DATA_VERSION_PROPERTY);

        var map = GSON.getAdapter(JSON_TYPE).fromJsonTree(dynamic.getValue());
        if (map == null) {
            throw new JsonParseException("Found null for advancements");
        }

        map.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    Advancement advancement = InvSync.instance.advancementLoader.get(entry.getKey());
                    if (advancement == null) {
                        Logger.log(Level.WARN, MessageFormat.format("Ignored advancement '{0}' - it doesn't exist anymore?", entry.getKey()));
                        return;
                    }

                    this.initProgress(advancement, entry.getValue());
                });

        /* {@link PlayerAdvancementTracker#load(ServerAdvancementLoader) */
        // This should not be needed to do so?
        //rewardEmptyAdvancements(InvSync.instance.advancementLoader);
        //updateCompleted();
        beginTrackingAllAdvancements(InvSync.instance.advancementLoader);
    }

    @Override
    public synchronized JsonElement readAdvancementData() {
        Map<Identifier, AdvancementProgress> map = Maps.newHashMap();
        for (var entry : progress.entrySet()) {
            AdvancementProgress advancementProgress = entry.getValue();
            if (!advancementProgress.isAnyObtained())
                continue;
            map.put(entry.getKey().getId(), advancementProgress);
        }

        JsonElement jsonElement;
        try {
            jsonElement = GSON.toJsonTree(map);
        } catch (ArrayIndexOutOfBoundsException e) {
            // Minecraft bug: Corrupted advancement date causes ArrayIndexOutOfBoundsException
            // Attempt to sanitize by removing problematic advancements
            Logger.log(Level.ERROR, "Detected corrupted advancement data (invalid timestamp), attempting to sanitize...");
            Logger.log(Level.DEBUG, "Error details: " + e.getMessage());

            map = sanitizeAdvancementData(map);

            try {
                jsonElement = GSON.toJsonTree(map);
                Logger.log(Level.WARN, "Successfully sanitized advancement data by removing " +
                    (progress.size() - map.size()) + " corrupted advancement(s)");
            } catch (ArrayIndexOutOfBoundsException e2) {
                // If sanitization fails, return minimal valid structure
                Logger.log(Level.ERROR, "Failed to sanitize advancement data, returning empty advancements");
                map.clear();
                jsonElement = GSON.toJsonTree(map);
            }
        }

        jsonElement.getAsJsonObject().addProperty(DATA_VERSION_PROPERTY, SharedConstants.getGameVersion().getSaveVersion().toString());
        return jsonElement;
    }

    /**
     * Attempts to sanitize corrupted advancement data by fixing invalid timestamps
     * while preserving the advancement progress.
     */
    private Map<Identifier, AdvancementProgress> sanitizeAdvancementData(Map<Identifier, AdvancementProgress> originalMap) {
        Map<Identifier, AdvancementProgress> sanitizedMap = Maps.newHashMap();
        String safeTimestamp = Instant.now().toString(); // ISO-8601 format: "2025-11-16T12:34:56Z"

        for (var entry : originalMap.entrySet()) {
            try {
                // Test if this advancement can be serialized
                GSON.toJsonTree(entry.getValue());
                sanitizedMap.put(entry.getKey(), entry.getValue());
            } catch (ArrayIndexOutOfBoundsException e) {
                // Corrupted timestamp detected - attempt to fix it
                Logger.log(Level.WARN, "Detected corrupted timestamp in advancement: " + entry.getKey() + ", attempting to repair...");

                try {
                    AdvancementProgress fixed = fixCorruptedTimestamps(entry.getValue(), safeTimestamp);
                    if (fixed != null) {
                        sanitizedMap.put(entry.getKey(), fixed);
                        Logger.log(Level.INFO, "Successfully repaired advancement: " + entry.getKey());
                    } else {
                        Logger.log(Level.ERROR, "Failed to repair advancement: " + entry.getKey() + ", it will be lost");
                    }
                } catch (Exception e2) {
                    Logger.log(Level.ERROR, "Failed to repair advancement: " + entry.getKey() + " - " + e2.getMessage());
                }
            }
        }

        return sanitizedMap;
    }

    /**
     * Attempts to fix corrupted timestamps in an AdvancementProgress by serializing to JSON,
     * replacing invalid date strings, and deserializing back.
     */
    private AdvancementProgress fixCorruptedTimestamps(AdvancementProgress progress, String safeTimestamp) {
        try {
            // Try to serialize to JSON string (might fail)
            String jsonStr;
            try {
                jsonStr = GSON.toJson(progress);
            } catch (ArrayIndexOutOfBoundsException e) {
                // Serialization failed - need to manually construct JSON
                return reconstructAdvancementProgress(progress, safeTimestamp);
            }

            // If we got here, serialization worked (shouldn't happen, but handle it)
            return progress;

        } catch (Exception e) {
            Logger.log(Level.DEBUG, "Error in fixCorruptedTimestamps: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reconstructs an AdvancementProgress by creating a minimal JSON representation
     * with safe timestamps, then deserializing it back.
     */
    private AdvancementProgress reconstructAdvancementProgress(AdvancementProgress progress, String safeTimestamp) {
        try {
            // Build a minimal JSON representation manually
            JsonObject progressJson = new JsonObject();
            JsonObject criteriaJson = new JsonObject();

            // Use reflection to access the criteria map (try different field names for different mappings)
            Map<String, ?> obtainedCriteria = null;
            String[] possibleFieldNames = {"obtainedCriteria", "field_192158_b", "b"}; // mapped, intermediary, obfuscated

            for (String fieldName : possibleFieldNames) {
                try {
                    var progressField = AdvancementProgress.class.getDeclaredField(fieldName);
                    progressField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, ?> criteria = (Map<String, ?>) progressField.get(progress);
                    if (criteria != null) {
                        obtainedCriteria = criteria;
                        Logger.log(Level.DEBUG, "Successfully accessed field: " + fieldName);
                        break;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Try next field name
                }
            }

            if (obtainedCriteria == null) {
                Logger.log(Level.DEBUG, "Could not access criteria field with any known field name");
                return null;
            }

            // For each obtained criterion, add it with a safe timestamp
            for (String criterion : obtainedCriteria.keySet()) {
                criteriaJson.addProperty(criterion, safeTimestamp);
            }

            progressJson.add("criteria", criteriaJson);

            // Deserialize back to AdvancementProgress
            AdvancementProgress reconstructed = GSON.fromJson(progressJson, AdvancementProgress.class);

            // Verify it can be serialized without errors
            GSON.toJsonTree(reconstructed);

            Logger.log(Level.DEBUG, "Successfully reconstructed advancement with " + obtainedCriteria.size() + " criteria");
            return reconstructed;

        } catch (IllegalAccessException e) {
            Logger.log(Level.DEBUG, "Could not access criteria field - access denied: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Logger.log(Level.DEBUG, "Failed to reconstruct AdvancementProgress: " + e.getMessage());
            return null;
        }
    }
}
