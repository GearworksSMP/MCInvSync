package arnaria.invsync.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.Level;
import org.lwjgl.system.CallbackI;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static arnaria.invsync.Invsync.MODID;
import static arnaria.invsync.Invsync.log;

public class ConfigManager {

    public static File configFile;

    public static String mySQL_User;
    public static String mySQL_Password;
    public static String mySQL_Server_Address;
    public static String mySQL_Server_Port;
    public static String mySQL_Database_Name;
    public static String mySQL_Database_Table_Name;
    
    public static boolean Sync_Inv;
    public static boolean Sync_Armour;
    public static boolean Sync_eChest;
    public static boolean Sync_Xp;
    public static boolean Sync_Score;
    public static boolean Sync_Health;
    public static boolean Sync_Food_Level;
    public static boolean Sync_Saturation;

    public static void prepareConfigFile() {
        if (configFile != null) {
            return;
        }
        configFile = new File(FabricLoader.getInstance().getConfigDir().toString(), MODID + "Config.json");
    }

    public static void createConfig() {
        JsonObject invSyncProperties = new JsonObject();

        invSyncProperties.addProperty("mySQL_User", "admin");
        invSyncProperties.addProperty("mySQL_Password", "1234");
        invSyncProperties.addProperty("mySQL_Server_Address", "0.0.0.0");
        invSyncProperties.addProperty("mySQL_Server_Port", "3301");
        invSyncProperties.addProperty("mySQL_Database_Name", "database");
        invSyncProperties.addProperty("mySQL_Database_Table_Name", "playerData"); //This is optional

        invSyncProperties.addProperty("Sync_Inv", true);
        invSyncProperties.addProperty("Sync_Armour", true);
        invSyncProperties.addProperty("Sync_eChest", true);
        invSyncProperties.addProperty("Sync_Xp", true);
        invSyncProperties.addProperty("Sync_Score", true);
        invSyncProperties.addProperty("Sync_Health", true);
        invSyncProperties.addProperty("Sync_Food_Level", true);
        invSyncProperties.addProperty("Sync_Saturation", true);

        jsonWriter(invSyncProperties);
    }

    public static void jsonWriter(JsonObject obj) {
        try {
            FileWriter file = new FileWriter(configFile);
            file.write(obj.toString());
            file.flush();
        } catch (IOException e) {
            log(Level.ERROR, "Oh no! Failed to create config file for " + MODID + "! This is either a bug or an incompatibility");
        }
    }

    public static void loadConfig() {
        prepareConfigFile();
        if (!configFile.exists()) createConfig();
    }
}
