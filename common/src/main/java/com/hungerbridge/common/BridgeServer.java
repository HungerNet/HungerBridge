package com.hungerbridge.common;

import com.hungerbridge.common.http.v2.InfoHandler;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BridgeServer (v2-only). Registers /v2/* endpoints based on config.
 */
public final class BridgeServer {

    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    private HttpServer server;
    private ExecutorService pool;

    public BridgeServer(Config config, Logger logger, CommandExecutor executor) {
        this.config = config;
        this.logger = logger;
        this.executor = executor;
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

        // v2 endpoints only
        if (config.isPingEnabled()) {
            server.createContext("/v2/ping", new PingHandler(config, logger));
        }
        if (config.isInfoEnabled()) {
            server.createContext("/v2/info", new InfoHandler(config, logger));
        }
        if (config.isStatusEnabled()) {
            server.createContext("/v2/status", new StatusHandler(config, logger));
        }
        if (config.isRunEnabled()) {
            server.createContext("/v2/run", new RunHandler(config, logger, executor));
        }
        if (config.isLogEnabled()) {
            server.createContext("/v2/log", new LogHandler(config, logger));
        }
        if (config.isStreamLogsEnabled()) {
            server.createContext("/stream/logs", new StreamLogsHandler(config));
        }
        if (config.isTpsEnabled()) {
            server.createContext("/v2/tps", new TpsHandler(config, logger, executor));
        }
        if (config.isPlayersEnabled()) {
            server.createContext("/v2/players", new PlayersHandler(config, logger, executor));
        }

        server.start();
        logger.log("INFO", "HungerBridge HTTP server started on port " + config.getPort());
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
        logger.log("INFO", "HungerBridge HTTP server stopped.");
    }
}
