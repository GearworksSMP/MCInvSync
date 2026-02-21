package de.michiruf.invsync.data;

import com.j256.ormlite.jdbc.JdbcConnectionSource;

import java.sql.SQLException;

/**
 * Lightweight migration shim.
 *
 * Some branches reference migration hooks but do not include implementation files.
 * Keeping these no-op methods preserves compatibility and unblocks builds.
 */
public final class DatabaseMigration {

    private DatabaseMigration() {}

    public static void migratePlayerDataTable(JdbcConnectionSource connection) throws SQLException {
        // no-op compatibility shim
    }

    public static void migratePlayerDataHistoryTable(JdbcConnectionSource connection) throws SQLException {
        // no-op compatibility shim
    }
}
