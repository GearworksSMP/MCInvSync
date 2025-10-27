package de.michiruf.invsync;

import de.michiruf.invsync.config.Config;
import de.michiruf.invsync.config.ConfigWrapper;
import de.michiruf.invsync.data.ORMLite;
import de.michiruf.invsync.data.PersistenceUtil;
import de.michiruf.invsync.data.entity.Statistics;
import de.michiruf.invsync.event.DelegatingEventsHandler;
import de.michiruf.invsync.event.InvSyncEventsHandler;
import de.michiruf.invsync.scheduler.TickScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class InvSync implements ModInitializer {

    public static InvSync instance;
    public Config config;

    public ORMLite database;
    public ServerAdvancementLoader advancementLoader;
    private static MinecraftServer SERVER_INSTANCE;

    @Override
    public void onInitialize() {
        Logger.log(Level.INFO, "Initializing InvSync...");
        // Initialize the TickScheduler
        TickScheduler.initialize();
        instance = this;
        initConfig();
        initDatabase();
        registerEvents();
        registerStatistics();
        Logger.log(Level.INFO, "Initialized InvSync");
    }

    private void registerStatistics() {
        TickScheduler.scheduleRepeating(() -> {
            // Capture lightweight snapshot on game thread
            final double tps = calculateTPS();
            final long usedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
            final long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            final long uptimeSeconds = getServer().getTicks() / 20;
            final int onlinePlayers = getServer().getPlayerManager().getPlayerList().size();

            // Create snapshots of collections to avoid holding references to game objects
            final java.util.List<Integer> playerPings = new java.util.ArrayList<>();
            final java.util.List<Integer> playerDeaths = new java.util.ArrayList<>();
            final java.util.List<Integer> playerLevels = new java.util.ArrayList<>();

            for (ServerPlayerEntity player : getServer().getPlayerManager().getPlayerList()) {
                playerPings.add(player.pingMilliseconds);
                playerDeaths.add(player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS)));
                playerLevels.add(player.experienceLevel);
            }

            final java.util.List<Integer> chunkCounts = new java.util.ArrayList<>();
            final java.util.List<Long> entityCounts = new java.util.ArrayList<>();

            for (ServerWorld world : getServer().getWorlds()) {
                chunkCounts.add(world.getChunkManager().getLoadedChunkCount());
                // Count entities efficiently using streams
                long count = StreamSupport.stream(world.iterateEntities().spliterator(), false).count();
                entityCounts.add(count);
            }

            // Heavy computation and DB write async - off game thread
            database.transactionAsync(() -> {
                try {
                    // Compute metrics from snapshots
                    int loadedChunks = chunkCounts.stream().mapToInt(Integer::intValue).sum();
                    long entityCount = entityCounts.stream().mapToLong(Long::longValue).sum();

                    double averagePing = 0.0;
                    if (!playerPings.isEmpty()) {
                        averagePing = playerPings.stream().mapToInt(Integer::intValue).average().orElse(0.0);
                    }

                    long totalPlayerDeaths = playerDeaths.stream().mapToInt(Integer::intValue).sum();
                    double averagePlayerLevel = playerLevels.stream().mapToInt(Integer::intValue).average().orElse(0.0);

                    Date insertDate = java.sql.Date.from(Instant.now());
                    Statistics stats = new Statistics(insertDate);

                    stats.serverName = config.serverName;
                    stats.serverTick = tps;
                    stats.memoryUsage = usedMemory;
                    stats.maxMemory = maxMemory;
                    stats.loadedChunks = loadedChunks;
                    stats.entityCount = entityCount;
                    stats.uptimeSeconds = uptimeSeconds;
                    stats.averagePlayerPing = averagePing;
                    stats.playersOnline = onlinePlayers;
                    stats.averagePlayerDeathCount = totalPlayerDeaths;
                    stats.averagePlayerLevel = averagePlayerLevel;

                    stats.prepareSave(config);
                    database.statisticsDao.create(stats);

                    Logger.log(Level.DEBUG, "Statistics saved successfully");
                    return null;
                } catch (Exception e) {
                    Logger.logException(Level.ERROR, e);
                    Logger.log(Level.ERROR, "Failed to save statistics, will retry next interval");
                    // Don't throw - allow task to continue
                    return null;
                }
            }).exceptionally(ex -> {
                Logger.logException(Level.ERROR, ex);
                Logger.log(Level.ERROR, "Statistics collection failed, will retry next interval");
                return null;
            });

        }, 1200);
    }

    private double calculateTPS() {
        double avgTickMs = getServer().getTickTime();
        // TPS = 1000ms / average tick time
        // If tick time is 50ms, TPS = 1000/50 = 20
        return avgTickMs > 0 ? Math.min(20.0, 1000.0 / avgTickMs) : 20.0;
    }

    private void initConfig() {
        try {
            config = ConfigWrapper.load().config;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void initDatabase() {
        try {
            PersistenceUtil.registerCustomPersisters();
            database = switch (config.databaseType) {
                case SQLITE -> ORMLite.connectSQLITE(config.sqlite.path, config.debugDeleteTables);
                case MYSQL -> ORMLite.connect(
                        "mysql",
                        config.mysql.database,
                        config.mysql.address,
                        config.mysql.port,
                        config.mysql.username,
                        config.mysql.password,
                        config.debugDeleteTables);
                case POSTGRES -> ORMLite.connect(
                        "postgres",
                        config.postgres.database,
                        config.postgres.address,
                        config.postgres.port,
                        config.postgres.username,
                        config.postgres.password,
                        config.debugDeleteTables);
                default -> null;
            };
        } catch (Exception e) {
            Logger.log(Level.ERROR, "Database could not get initialized");
            Logger.logException(Level.ERROR, e);
            System.exit(1);
            return;
        }

        if (database == null) {
            Logger.log(Level.ERROR, MessageFormat.format("Configured database type {0} is not available", config.databaseType));
            System.exit(1);
        }

        // Schedule async history cleanup to avoid blocking server startup
        database.scheduleHistoryCleanup();
    }

    private void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> advancementLoader = server.getAdvancementLoader());
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStart);
        DelegatingEventsHandler.registerMinecraftEvents(database, config);
        InvSyncEventsHandler.registerEvents(config);
    }

    private void onServerStart(MinecraftServer server) {
        SERVER_INSTANCE = server;
        Logger.log(Level.DEBUG, "Server started. Server instance captured.");
    }

    public static MinecraftServer getServer() {
        return SERVER_INSTANCE;
    }


    // TODO Register a command to synchronize offline players
//    private static void registerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
//        LiteralCommandNode<ServerCommandSource> proxyCommand = CommandManager
//                .literal("invsync")
//                .requires(cmd -> cmd.hasPermissionLevel(4))
//                .then(CommandManager.argument("command", StringArgumentType.string())
//                        .executes(ProxyCommandMod::sendMessage)
//                        .build())
//                .build();
//        dispatcher.getRoot().addChild(proxyCommand);
//    }
}
