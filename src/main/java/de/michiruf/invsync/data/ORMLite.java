package de.michiruf.invsync.data;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.DeleteBuilder;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;
import de.michiruf.invsync.Logger;
import de.michiruf.invsync.data.custom_schema.OverloadableDatabaseTableConfig;
import de.michiruf.invsync.data.entity.PlayerData;
import de.michiruf.invsync.data.entity.PlayerDataHistory;
import de.michiruf.invsync.data.entity.PlayerAdvancements;
import de.michiruf.invsync.data.entity.PlayerAdvancementsHistory;
import de.michiruf.invsync.data.entity.Statistics;
import org.apache.logging.log4j.Level;

import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author Michael Ruf
 * @since 2023-01-05
 */
public class ORMLite implements AutoCloseable {

    public static ORMLite connectSQLITE(String filename, boolean debugDeleteTables) throws Exception {
        var url = MessageFormat.format("jdbc:sqlite:{0}", filename);
        try (var connection = new JdbcConnectionSource(url)) {
            return new ORMLite(connection, debugDeleteTables);
        }
    }

    public static ORMLite connect(String type, String database, String host, int port, String username, String password, boolean debugDeleteTables) throws Exception {
        // Add connection pooling parameters for better performance
        var url = MessageFormat.format(
            "jdbc:{0}://{2}:{3,number,#}/{1}?serverTimezone=UTC&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true&rewriteBatchedStatements=true",
            type, database, host, port);

        // Don't use try-with-resources - we need to keep the connection alive
        var connection = new JdbcConnectionSource(url, username, password);
        Logger.log(Level.INFO, "Connected with connection pooling: " + url);
        return new ORMLite(connection, debugDeleteTables);
    }

    private final ConnectionSource connection;
    public final Dao<PlayerData, String> playerDataDao;
    public final Dao<PlayerDataHistory, String> playerDataHistoryDao;
    public final Dao<PlayerAdvancements, String> playerAdvancementsDao;
    public final Dao<PlayerAdvancementsHistory, String> playerAdvancementsHistoryDao;
    public final Dao<Statistics, String> statisticsDao;

    // Thread pool for async database operations (bounded to prevent thread exhaustion)
    private static final ExecutorService dbExecutor = Executors.newFixedThreadPool(
        Math.max(4, Runtime.getRuntime().availableProcessors()),
        r -> {
            Thread t = new Thread(r, "InvSync-DB-Worker");
            t.setDaemon(true);
            return t;
        }
    );

    public ORMLite(JdbcConnectionSource connection, boolean debugDeleteTables) throws SQLException {
        this.connection = connection;

        // player data table
        var playerDataConfig = OverloadableDatabaseTableConfig.fromClass(connection.getDatabaseType(), PlayerData.class);
        if (debugDeleteTables) {
            TableUtils.dropTable(connection, playerDataConfig, true);
        }
        TableUtils.createTableIfNotExists(connection, playerDataConfig);
        playerDataDao = DaoManager.createDao(connection, playerDataConfig);

        // Run migration to add new columns to existing tables
        try {
            DatabaseMigration.migratePlayerDataTable((JdbcConnectionSource) connection);
        } catch (SQLException e) {
            Logger.log(Level.WARN, "Failed to migrate player_data table, columns may already exist");
            Logger.logException(Level.DEBUG, e);
        }

        //history table
        var playerDataHistoryConfig = OverloadableDatabaseTableConfig.fromClass(connection.getDatabaseType(), PlayerDataHistory.class);
        if (debugDeleteTables) {
            TableUtils.dropTable(connection, playerDataHistoryConfig, true);
        }
        TableUtils.createTableIfNotExists(connection, playerDataHistoryConfig);
        playerDataHistoryDao = DaoManager.createDao(connection, playerDataHistoryConfig);

        // Run migration to add new columns to history table
        try {
            DatabaseMigration.migratePlayerDataHistoryTable((JdbcConnectionSource) connection);
        } catch (SQLException e) {
            Logger.log(Level.WARN, "Failed to migrate player_data_history table, columns may already exist");
            Logger.logException(Level.DEBUG, e);
        }

        // History cleanup moved to async background task after initialization
        // See scheduleHistoryCleanup() method

        // player advancements table (separate from main player data for optimization)
        var playerAdvancementsConfig = OverloadableDatabaseTableConfig.fromClass(connection.getDatabaseType(), PlayerAdvancements.class);
        if (debugDeleteTables) {
            TableUtils.dropTable(connection, playerAdvancementsConfig, true);
        }
        TableUtils.createTableIfNotExists(connection, playerAdvancementsConfig);
        playerAdvancementsDao = DaoManager.createDao(connection, playerAdvancementsConfig);

        // player advancements history table
        var playerAdvancementsHistoryConfig = OverloadableDatabaseTableConfig.fromClass(connection.getDatabaseType(), PlayerAdvancementsHistory.class);
        if (debugDeleteTables) {
            TableUtils.dropTable(connection, playerAdvancementsHistoryConfig, true);
        }
        TableUtils.createTableIfNotExists(connection, playerAdvancementsHistoryConfig);
        playerAdvancementsHistoryDao = DaoManager.createDao(connection, playerAdvancementsHistoryConfig);

        //statistics table
        var statisticsConfig = OverloadableDatabaseTableConfig.fromClass(connection.getDatabaseType(), Statistics.class);
        if (debugDeleteTables) {
            TableUtils.dropTable(connection, statisticsConfig, true);
        }
        TableUtils.createTableIfNotExists(connection, statisticsConfig);
        statisticsDao = DaoManager.createDao(connection, statisticsConfig);
    }

    public void transaction(Runnable transaction) {
        transaction(transaction, null);
    }

    public void transaction(Runnable transaction, Consumer<SQLException> onError) {
        try {
            TransactionManager.callInTransaction(connection, () -> {
                transaction.run();
                return null;
            });
        } catch (SQLException e) {
            if (onError != null)
                onError.accept(e);
        }
    }

    /**
     * Executes a database transaction asynchronously.
     *
     * @param transaction The transaction to execute
     * @return CompletableFuture that completes when transaction is done
     */
    public CompletableFuture<Void> transactionAsync(Runnable transaction) {
        return CompletableFuture.runAsync(() -> {
            try {
                TransactionManager.callInTransaction(connection, () -> {
                    transaction.run();
                    return null;
                });
            } catch (SQLException e) {
                throw new RuntimeException("Database transaction failed", e);
            }
        }, dbExecutor);
    }

    /**
     * Executes a database transaction asynchronously with a result.
     *
     * @param transaction The transaction to execute
     * @param <T> The return type
     * @return CompletableFuture with the result
     */
    public <T> CompletableFuture<T> transactionAsync(Supplier<T> transaction) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return TransactionManager.callInTransaction(connection, transaction::get);
            } catch (SQLException e) {
                throw new RuntimeException("Database transaction failed", e);
            }
        }, dbExecutor);
    }

    @Override
    public void close() throws Exception {
        Logger.log(Level.INFO, "Shutting down database connection...");

        // Stop accepting new tasks
        dbExecutor.shutdown();

        try {
            // Wait for existing tasks to complete (with timeout)
            if (!dbExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                Logger.log(Level.WARN, "Database executor did not terminate in time, forcing shutdown");
                java.util.List<Runnable> droppedTasks = dbExecutor.shutdownNow();
                Logger.log(Level.WARN, "Dropped " + droppedTasks.size() + " pending database tasks");
            } else {
                Logger.log(Level.INFO, "All database tasks completed successfully");
            }
        } catch (InterruptedException e) {
            Logger.log(Level.ERROR, "Interrupted during shutdown, forcing immediate shutdown");
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        connection.close();
        Logger.log(Level.INFO, "Database connection closed");
    }

    /**
     * Schedules asynchronous cleanup of old player data history records.
     * This should be called after database initialization to avoid blocking server startup.
     * Deletes history records older than 3 days.
     */
    public void scheduleHistoryCleanup() {
        CompletableFuture.runAsync(() -> {
            try {
                Logger.log(Level.INFO, "Starting background history cleanup...");

                // Clean up player_data_history
                DeleteBuilder<PlayerDataHistory, String> db = playerDataHistoryDao.deleteBuilder();
                db.where().le("creationDate", java.sql.Date.from(Instant.now().minus(3, ChronoUnit.DAYS)));
                int deleted = db.delete();
                Logger.log(Level.INFO, "Player data history cleanup: deleted " + deleted + " old records");

                // Clean up player_advancements_history
                DeleteBuilder<PlayerAdvancementsHistory, String> advDb = playerAdvancementsHistoryDao.deleteBuilder();
                advDb.where().le("creationDate", java.sql.Date.from(Instant.now().minus(3, ChronoUnit.DAYS)));
                int advDeleted = advDb.delete();
                Logger.log(Level.INFO, "Advancement history cleanup: deleted " + advDeleted + " old records");

                Logger.log(Level.INFO, "History cleanup complete: deleted " + (deleted + advDeleted) + " total records");
            } catch (SQLException e) {
                Logger.logException(Level.ERROR, e);
                Logger.log(Level.ERROR, "Failed to clean up old history records");
            }
        }, dbExecutor);
    }
}
