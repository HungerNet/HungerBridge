package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

public final class SystemGcHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;

    public SystemGcHandler(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtil.error(ex, 405, "method_not_allowed", "Use GET", config);
            return;
        }
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "system.gc")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access GC", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "system.gc")) return;

        long totalCollections = 0L;
        long totalTimeMs = 0L;
        long lastPauseMs = 0L;
        long avgPauseMs = 0L;
        String gcType = "unknown";

        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            long time = bean.getCollectionTime();
            if (count >= 0L) totalCollections += count;
            if (time >= 0L) totalTimeMs += time;
            if (bean.getCollectionCount() > 0L) {
                lastPauseMs = Math.max(lastPauseMs, bean.getCollectionTime());
            }
            if (bean.getName() != null && !bean.getName().isBlank()) {
                gcType = bean.getName();
            }
        }

        if (totalCollections > 0L) {
            avgPauseMs = totalTimeMs / totalCollections;
        }

        JsonObject resp = Json.obj(
                "ok", true,
                "gc_type", gcType,
                "gc_count", totalCollections,
                "gc_time_ms", totalTimeMs,
                "last_gc_pause_ms", lastPauseMs,
                "avg_gc_pause_ms", avgPauseMs
        );
        HttpUtil.writeJson(ex, 200, resp);
    }
}
