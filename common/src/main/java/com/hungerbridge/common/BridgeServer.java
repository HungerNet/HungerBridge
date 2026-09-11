package com.hungerbridge.common;

// InfoHandler and StatusHandler removed per canonical API surface
import com.hungerbridge.common.http.v2.LogHandler;
import com.hungerbridge.common.http.v2.PingHandler;
import com.hungerbridge.common.http.v2.PlayersHandler;
import com.hungerbridge.common.http.v2.RunHandler;
import com.hungerbridge.common.http.v2.StatusHandler;
import com.hungerbridge.common.http.v2.StreamLogsHandler;
import com.hungerbridge.common.http.v2.TpsHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BridgeServer. Registers HTTP endpoints based on config.
 */
public final class BridgeServer {

    private final Path configDir;
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;
    private final Runnable minecraftStopHandler;

    private HttpServer server;
    private ExecutorService pool;

    public BridgeServer(Path configDir, Config config, Logger logger, CommandExecutor executor) {
        this(configDir, config, logger, executor, null);
    }

    public BridgeServer(Path configDir, Config config, Logger logger, CommandExecutor executor, Runnable minecraftStopHandler) {
        this.configDir = configDir;
        this.config = config;
        this.logger = logger;
        this.executor = executor;
        this.minecraftStopHandler = minecraftStopHandler;
    }

    public synchronized void start() {
        if (server != null) return;

        try {
            server = HttpServer.create(new InetSocketAddress(config.getPort()), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind HTTP server", e);
        }

        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);

        // endpoints (root-level API)
        java.util.List<String> endpoints = new java.util.ArrayList<>();

        server.createContext("/ping", new PingHandler(config, logger));
        endpoints.add("/ping");
        server.createContext("/auth/check", new com.hungerbridge.common.http.v2.AuthCheckHandler(config));
        endpoints.add("/auth/check");
        server.createContext("/server/run", new RunHandler(config, logger, executor));
        endpoints.add("/server/run");
        
        server.createContext("/server/stop", new com.hungerbridge.common.http.v2.ServerStopHandler(config, logger, this));
        endpoints.add("/server/stop");
        server.createContext("/server/log", new LogHandler(config, logger));
        endpoints.add("/server/log");
        server.createContext("/server/meta", new com.hungerbridge.common.http.v2.MetaHandler(config, logger));
        endpoints.add("/server/meta");
        server.createContext("/server/stream", new StreamLogsHandler(config));
        endpoints.add("/server/stream");
        server.createContext("/system/uptime", new com.hungerbridge.common.http.v2.SystemUptimeHandler(config, logger));
        endpoints.add("/system/uptime");
        server.createContext("/system/cpu", new com.hungerbridge.common.http.v2.SystemCpuHandler(config, logger));
        endpoints.add("/system/cpu");
        server.createContext("/system/memory", new com.hungerbridge.common.http.v2.SystemMemoryHandler(config, logger));
        endpoints.add("/system/memory");
        server.createContext("/system/gc", new com.hungerbridge.common.http.v2.SystemGcHandler(config, logger));
        endpoints.add("/system/gc");
        server.createContext("/system/threads", new com.hungerbridge.common.http.v2.SystemThreadsHandler(config, logger));
        endpoints.add("/system/threads");
        server.createContext("/system/network", new com.hungerbridge.common.http.v2.SystemNetworkHandler(config, logger));
        endpoints.add("/system/network");
        server.createContext("/system/disk", new com.hungerbridge.common.http.v2.SystemDiskHandler(config, logger));
        endpoints.add("/system/disk");
        server.createContext("/players/list", new com.hungerbridge.common.http.v2.PlayersListHandler(config, logger, executor));
        endpoints.add("/players/list");
        server.createContext("/world/tps", new com.hungerbridge.common.http.v2.WorldTpsHandler(config, logger, executor));
        endpoints.add("/world/tps");
        server.createContext("/world/mspt", new com.hungerbridge.common.http.v2.WorldMsptHandler(config, logger, executor));
        endpoints.add("/world/mspt");
        server.createContext("/world/chunks", new com.hungerbridge.common.http.v2.WorldChunksHandler(config, logger, executor));
        endpoints.add("/world/chunks");
        server.createContext("/world/entities", new com.hungerbridge.common.http.v2.WorldEntitiesHandler(config, logger, executor));
        endpoints.add("/world/entities");
        server.createContext("/world/time", new com.hungerbridge.common.http.v2.WorldTimeHandler(config, logger, executor));
        endpoints.add("/world/time");
        server.createContext("/world/weather", new com.hungerbridge.common.http.v2.WorldWeatherHandler(config, logger, executor));
        endpoints.add("/world/weather");
        server.createContext("/pickup", new com.hungerbridge.common.http.v2.PickupHandler(config));
        endpoints.add("/pickup/{id}");
        // legacy aliases removed: prefer canonical v3 routes (e.g. /world/tps, /players/list)

        server.start();
        if (logger != null) logger.log("INFO", "HungerBridge HTTP server started on port " + config.getPort());
        if (logger != null) logger.log("INFO", "Registered endpoints: " + String.join(", ", endpoints));
    }

    public synchronized void stop() {
        LogDistributor.get().close();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        // shutdown TokenManager sweeper if present
        try {
            if (config != null) {
                com.hungerbridge.common.security.TokenManager tm = config.getTokenManager();
                if (tm != null) tm.shutdown();
            }
        } catch (Exception ignored) {}

        logger.log("INFO", "HungerBridge HTTP server stopped.");
    }

    public void stopMinecraftServer() {
        if (minecraftStopHandler != null) {
            minecraftStopHandler.run();
        }
    }

    public Logger getLogger() {
        return logger;
    }

    public Config getConfig() { return config; }

    public synchronized boolean reloadConfig() {
        try {
            com.hungerbridge.common.TokensConfig tc = com.hungerbridge.common.TokensConfig.load(configDir, logger);
            config.setTokensConfig(tc);
            if (logger != null) logger.log("INFO", "Reloaded runtime config from disk.");
            return true;
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to reload config: " + e.getMessage());
            return false;
        }
    }
}
