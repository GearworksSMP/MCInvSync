package mrnavastar.invsync;

import mc.microconfig.Comment;
import mc.microconfig.ConfigData;

public class Config implements ConfigData {

    @Comment("Allowed values: \"SQLITE\" | \"MYSQL\" | \"H2\"")
    public String DATABASE_TYPE = "H2";

    // Sqlite
    public String SQLITE_PATH = "/path/to/database/InvSync.db";

    // Mysql
    public String MYSQL_DATABASE = "InvSync";
    public String MYSQL_ADDRESS = "127.0.0.1";
    public String MYSQL_PORT = "3306";
    public String MYSQL_USERNAME = "username";
    public String MYSQL_PASSWORD = "password";

    @Comment("Sync settings")
    public boolean SYNC_INVENTORY = true;
    public boolean SYNC_ENDER_CHEST = true;
    public boolean SYNC_HEALTH = true;
    public boolean SYNC_FOOD_LEVEL = true;
    public boolean SYNC_XP_LEVEL = true;
    public boolean SYNC_SCORE = true;
    public boolean SYNC_STATUS_EFFECTS = true;
    public boolean SYNC_ADVANCEMENTS = true;

    @Comment("Initial synchronization settings")
    public boolean INITIAL_SYNC_ENABLED = true;
    @Comment("Allowed values: \"SKIP\" | \"OVERWRITE\"")
    public String INITIAL_SYNC_MODE = "SKIP";
    public String INITIAL_SYNC_SERVER_NAME = "undefined";
}
