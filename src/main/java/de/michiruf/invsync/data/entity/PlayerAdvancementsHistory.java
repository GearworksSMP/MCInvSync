package de.michiruf.invsync.data.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import de.michiruf.invsync.data.custom_schema.DatabaseTypeSpecificDatabaseField;
import de.michiruf.invsync.data.custom_schema.DatabaseTypeSpecificOverload;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@DatabaseTable(tableName = "player_advancements_history")
public class PlayerAdvancementsHistory {

    @DatabaseField(generatedId = true)
    public long id;

    @DatabaseField
    public String playerUuid;

    @DatabaseField
    public int version;

    @DatabaseField
    public Date creationDate;

    @DatabaseField
    @DatabaseTypeSpecificDatabaseField({
        @DatabaseTypeSpecificOverload(
            typeName = "MySQL",
            databaseField = @DatabaseField(columnDefinition = "LONGTEXT")
        )
    })
    public JsonElement advancements = new JsonObject();

    public PlayerAdvancementsHistory() {
        // ORMLite no-arg constructor
    }

    public PlayerAdvancementsHistory(UUID uuid, int version, JsonElement advancements) {
        this.playerUuid = uuid.toString();
        this.version = version;
        this.advancements = advancements;
        this.creationDate = java.sql.Date.from(Instant.now());
    }
}
