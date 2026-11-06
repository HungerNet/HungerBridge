package com.hungerbridge.common;

import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.http.RequestState;
import com.hungerbridge.common.http.v2.AuthCheckHandler;
import com.hungerbridge.common.http.v2.LogHandler;
import com.hungerbridge.common.http.v2.MetaHandler;
import com.hungerbridge.common.http.v2.NotFoundHandler;
import com.hungerbridge.common.http.v2.PickupHandler;
import com.hungerbridge.common.http.v2.PingHandler;
import com.hungerbridge.common.http.v2.PlayersListHandler;
import com.hungerbridge.common.http.v2.RunHandler;
import com.hungerbridge.common.http.v2.ServerStopHandler;
import com.hungerbridge.common.http.v2.StreamLogsHandler;
import com.hungerbridge.common.http.v2.SystemHandlers;
import com.hungerbridge.common.http.v2.WorldHandlers;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HungerBridge HTTP API (loader independent). Platform modules supply a
 * {@link CommandExecutor} and a stop action.
 */
public final class BridgeServer {

    /** Wire-protocol major version, advertised in {@value #PROTOCOL_HEADER} and /server/meta. */
    public static final int PROTOCOL_VERSION = 1;
    public static final String PROTOCOL_HEADER = "X-HungerBridge-Protocol";

    /**
     * Worker threads. SSE streams hold a thread for their lifetime, so the pool must grow
     * on demand (direct hand-off) instead of queueing behind a few core threads.
     */
    private static final int MAX_WORKERS = 128;

    private final Path configDir;
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;
    private final Runnable minecraftStopHandler;

    private HttpServer server;
    private ThreadPoolExecutor pool;

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

        String bindAddress = config.getBindAddress() != null && !config.getBindAddress().isBlank() ? config.getBindAddress() : "127.0.0.1";
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, config.getPort()), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind HungerBridge HTTP server to " + bindAddress + ":" + config.getPort(), e);
        }

        AtomicInteger threadIds = new AtomicInteger();
        pool = new ThreadPoolExecutor(4, MAX_WORKERS, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "HungerBridge-Http-" + threadIds.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(pool);
        HttpUtil.resetRateLimits();

        Map<String, HttpHandler> routes = new LinkedHashMap<>();
        List<ApiHandler> api = new ArrayList<>();
        api.add(new PingHandler(config, logger));
        api.add(new AuthCheckHandler(config, logger));
        api.add(new RunHandler(config, logger, executor));
        api.add(new ServerStopHandler(config, logger, this::stopMinecraftServer));
        api.add(new LogHandler(config, logger));
        api.add(new MetaHandler(config, logger, this::getPort));
        api.add(new StreamLogsHandler(config, logger));
        api.add(new PlayersListHandler(config, logger, executor));
        api.addAll(SystemHandlers.all(config, logger));
        api.addAll(WorldHandlers.all(config, logger, executor));
        for (ApiHandler h : api) routes.put(h.path(), h);
        routes.put("/pickup", new PickupHandler(config));
        routes.put("/", new NotFoundHandler(config));

        for (Map.Entry<String, HttpHandler> e : routes.entrySet()) {
            HttpContext context = server.createContext(e.getKey(), e.getValue());
            context.getFilters().add(new RequestStateFilter());
            context.getFilters().add(new ProtocolHeaderFilter());
            context.getFilters().add(new RemoteIpFilter(config));
            context.getFilters().add(new AuditFilter(config));
        }

        server.start();
        if (logger != null) {
            logger.log("INFO", "HungerBridge HTTP server listening on " + bindAddress + ":" + getPort() + " (protocol v" + PROTOCOL_VERSION + ")");
            routes.remove("/");
            logger.log("INFO", "Registered endpoints: " + String.join(", ", routes.keySet()));
        }
    }

    /** Actual bound port (differs from config when the configured port is 0). */
    public synchronized int getPort() {
        return server != null ? server.getAddress().getPort() : config.getPort();
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
        try {
            TokenManager tm = config != null ? config.getTokenManager() : null;
            if (tm != null) tm.shutdown();
        } catch (Exception ignored) {
        }
        if (logger != null) logger.log("INFO", "HungerBridge HTTP server stopped.");
    }

    public void stopMinecraftServer() {
        if (minecraftStopHandler != null) {
            minecraftStopHandler.run();
        }
    }

    /** Binds per-request state to the worker thread (see {@link RequestState}). */
    private static final class RequestStateFilter extends Filter {
        @Override
        public String description() {
            return "hungerbridge-request-state";
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            RequestState.of(exchange);
            try {
                chain.doFilter(exchange);
            } finally {
                RequestState.clear();
            }
        }
    }

    private static final class ProtocolHeaderFilter extends Filter {
        @Override
        public String description() {
            return "hungerbridge-protocol-header";
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            exchange.getResponseHeaders().set(PROTOCOL_HEADER, Integer.toString(PROTOCOL_VERSION));
            chain.doFilter(exchange);
        }
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
            if (config == null || !config.isRemoteAllowed(HttpUtil.remoteIp(exchange))) {
                HttpUtil.error(exchange, 403, "forbidden", "Remote IP not allowed by policy", config, "ip_not_allowed");
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
            String remoteIp = HttpUtil.remoteIp(exchange);
            String tokenId = exchange.getRequestHeaders().getFirst("X-Auth-Id");
            Map<String, String> params = new LinkedHashMap<>();
            String query = exchange.getRequestURI() != null ? exchange.getRequestURI().getQuery() : null;
            if (query != null && !query.isBlank()) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    params.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }
            try {
                chain.doFilter(exchange);
                com.google.gson.JsonObject json = RequestState.of(exchange).json();
                if (json != null) {
                    for (var entry : json.entrySet()) {
                        params.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().toString());
                    }
                }
                String result = exchange.getResponseCode() >= 400 ? "failure:" + exchange.getResponseCode() : "success";
                AuditLogger.logRequest(config != null ? config.getConfigDir() : null, path, method, remoteIp, tokenId, params, result);
            } catch (Throwable t) {
                AuditLogger.logRequest(config != null ? config.getConfigDir() : null, path, method, remoteIp, tokenId, params, "exception");
                throw t;
            }
        }
    }

    public Logger getLogger() {
        return logger;
    }

    public Config getConfig() {
        return config;
    }

    public synchronized boolean reloadConfig() {
        try {
            boolean ok = config.reload(logger);
            HttpUtil.resetRateLimits();
            return ok;
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to reload config: " + e.getMessage());
            return false;
        }
    }
}
