package com.hungerbridge.common;

// InfoHandler and StatusHandler removed per canonical API surface
import com.hungerbridge.common.http.v2.LogHandler;
import com.hungerbridge.common.http.v2.PingHandler;
import com.hungerbridge.common.http.v2.PlayersHandler;
import com.hungerbridge.common.http.v2.RunHandler;
import com.hungerbridge.common.http.v2.StatusHandler;
import com.hungerbridge.common.http.v2.StreamLogsHandler;
import com.hungerbridge.common.http.v2.TpsHandler;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Filter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
            String bindAddress = config != null && config.getBindAddress() != null && !config.getBindAddress().isBlank()
                    ? config.getBindAddress()
                    : "127.0.0.1";
            server = HttpServer.create(new InetSocketAddress(bindAddress, config.getPort()), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind HTTP server", e);
        }

        pool = new ThreadPoolExecutor(
                4,
                32,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                r -> {
                    Thread t = new Thread(r, "HungerBridge-Http");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        server.setExecutor(pool);

        // endpoints (root-level API)
        java.util.List<String> endpoints = new java.util.ArrayList<>();

        registerContext("/ping", new PingHandler(config, logger), endpoints);
        registerContext("/auth/check", new com.hungerbridge.common.http.v2.AuthCheckHandler(config), endpoints);
        registerContext("/server/run", new RunHandler(config, logger, executor), endpoints);
        registerContext("/server/stop", new com.hungerbridge.common.http.v2.ServerStopHandler(config, logger, this), endpoints);
        registerContext("/server/log", new LogHandler(config, logger), endpoints);
        registerContext("/server/meta", new com.hungerbridge.common.http.v2.MetaHandler(config, logger), endpoints);
        registerContext("/server/stream", new StreamLogsHandler(config), endpoints);
        registerContext("/system/uptime", new com.hungerbridge.common.http.v2.SystemUptimeHandler(config, logger), endpoints);
        registerContext("/system/cpu", new com.hungerbridge.common.http.v2.SystemCpuHandler(config, logger), endpoints);
        registerContext("/system/memory", new com.hungerbridge.common.http.v2.SystemMemoryHandler(config, logger), endpoints);
        registerContext("/system/gc", new com.hungerbridge.common.http.v2.SystemGcHandler(config, logger), endpoints);
        registerContext("/system/threads", new com.hungerbridge.common.http.v2.SystemThreadsHandler(config, logger), endpoints);
        registerContext("/system/network", new com.hungerbridge.common.http.v2.SystemNetworkHandler(config, logger), endpoints);
        registerContext("/system/disk", new com.hungerbridge.common.http.v2.SystemDiskHandler(config, logger), endpoints);
        registerContext("/players/list", new com.hungerbridge.common.http.v2.PlayersListHandler(config, logger, executor), endpoints);
        registerContext("/world/tps", new com.hungerbridge.common.http.v2.WorldTpsHandler(config, logger, executor), endpoints);
        registerContext("/world/mspt", new com.hungerbridge.common.http.v2.WorldMsptHandler(config, logger, executor), endpoints);
        registerContext("/world/chunks", new com.hungerbridge.common.http.v2.WorldChunksHandler(config, logger, executor), endpoints);
        registerContext("/world/entities", new com.hungerbridge.common.http.v2.WorldEntitiesHandler(config, logger, executor), endpoints);
        registerContext("/world/time", new com.hungerbridge.common.http.v2.WorldTimeHandler(config, logger, executor), endpoints);
        registerContext("/world/weather", new com.hungerbridge.common.http.v2.WorldWeatherHandler(config, logger, executor), endpoints);
        registerContext("/pickup", new com.hungerbridge.common.http.v2.PickupHandler(config), endpoints);
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

    private void registerContext(String path, com.sun.net.httpserver.HttpHandler handler, java.util.List<String> endpoints) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(new RemoteIpFilter(config));
        context.getFilters().add(new AuditFilter(config));
        endpoints.add(path);
    }

    private static final class RemoteIpFilter extends Filter {
        private final Config config;

        private RemoteIpFilter(Config config) {
            this.config = config;
        }

        @Override
        public String description() {
            return "remote-ip-whitelist";
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                java.net.InetSocketAddress remote = exchange.getRemoteAddress();
                String remoteIp = remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "";
                if (config == null || !config.isRemoteAllowed(remoteIp)) {
                    com.hungerbridge.common.http.HttpUtil.error(exchange, 403, "forbidden", "Remote IP not allowed by policy", config);
                    return;
                }
                chain.doFilter(exchange);
        }
    }

    private static final class AuditFilter extends Filter {
        private final Config config;

        private AuditFilter(Config config) {
            this.config = config;
        }

        @Override
        public String description() {
            return "hungerbridge-audit";
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "unknown";
            String method = exchange.getRequestMethod();
            String remoteIp = exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null
                    ? exchange.getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            String tokenId = exchange.getRequestHeaders().getFirst("X-Auth-Id");
            java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
            String query = exchange.getRequestURI() != null ? exchange.getRequestURI().getQuery() : null;
            if (query != null && !query.isBlank()) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 0) continue;
                    String key = kv[0];
                    String value = kv.length > 1 ? kv[1] : "";
                    params.put(key, value);
                }
            }
            Object jsonAttr = exchange.getAttribute("hb.request.json");
            if (jsonAttr instanceof com.google.gson.JsonObject json) {
                for (var entry : json.entrySet()) {
                    params.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().toString());
                }
            }
            try {
                chain.doFilter(exchange);
                int responseCode = exchange.getResponseCode();
                String result = responseCode >= 400 ? "failure" : "success";
                com.hungerbridge.common.AuditLogger.logRequest(config != null ? config.getConfigDir() : null, path, method, remoteIp, tokenId, params, result);
            } catch (Throwable t) {
                com.hungerbridge.common.AuditLogger.logRequest(config != null ? config.getConfigDir() : null, path, method, remoteIp, tokenId, params, "exception");
                throw t;
            }
        }
    }

    public Logger getLogger() {
        return logger;
    }

    public Config getConfig() { return config; }

    public synchronized boolean reloadConfig() {
        try {
            return config.reload(logger);
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to reload config: " + e.getMessage());
            return false;
        }
    }
}
