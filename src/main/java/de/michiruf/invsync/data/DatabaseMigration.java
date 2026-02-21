package de.michiruf.invsync.data;

import com.j256.ormlite.jdbc.JdbcConnectionSource;

import java.sql.SQLException;

/**
 * Runtime schema migrations for backward compatibility.
 */
public final class DatabaseMigration {

    private DatabaseMigration() {}

    public static void migratePlayerDataTable(JdbcConnectionSource connection) throws SQLException {
        // Keep existing hook for compatibility. No-op for now.
    }

    public static void migratePlayerDataHistoryTable(JdbcConnectionSource connection) throws SQLException {
        // Keep existing hook for compatibility. No-op for now.
    }

    public static void migratePlayerAdvancementsTable(JdbcConnectionSource connection) throws SQLException {
        // Ensure legacy tables get required columns when upgrading from older schema.
        addColumnIfMissing(connection, "player_advancements", "advancements", "LONGTEXT");
        addColumnIfMissing(connection, "player_advancements", "version", "INT NOT NULL DEFAULT 0");
    }

    public static void migratePlayerAdvancementsHistoryTable(JdbcConnectionSource connection) throws SQLException {
        addColumnIfMissing(connection, "player_advancements_history", "advancements", "LONGTEXT");
        addColumnIfMissing(connection, "player_advancements_history", "version", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "player_advancements_history", "creationDate", "DATETIME NULL");
    }

    private static void addColumnIfMissing(JdbcConnectionSource connection, String table, String column, String ddl) throws SQLException {
        String statement = "ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + ddl;
        try {
            connection.getReadWriteConnection(null).executeStatement(statement, 0);
        } catch (SQLException e) {
            // Ignore duplicate/exists errors across DB engines; rethrow everything else.
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            if (msg.contains("duplicate") || msg.contains("exists") || msg.contains("already")) {
                return;
            }
            throw e;
        }
    }
}
