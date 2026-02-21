package de.michiruf.invsync.data.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import de.michiruf.invsync.data.custom_schema.DatabaseTypeSpecificDatabaseField;
import de.michiruf.invsync.data.custom_schema.DatabaseTypeSpecificOverload;

import java.util.UUID;

@DatabaseTable(tableName = "player_advancements")
public class PlayerAdvancements {

    @DatabaseField(id = true)
    public String playerUuid;

    @DatabaseField
    public int version = 0;

    @DatabaseField
    @DatabaseTypeSpecificDatabaseField({
        @DatabaseTypeSpecificOverload(
            typeName = "MySQL",
            databaseField = @DatabaseField(columnDefinition = "LONGTEXT")
        )
    })
    public JsonElement advancements = new JsonObject();

    public PlayerAdvancements() {
        // ORMLite no-arg constructor
    }

    public PlayerAdvancements(UUID uuid) {
        this.playerUuid = uuid.toString();
    }

    public void touchAndBumpVersion() {
        this.version++;
    }
}
