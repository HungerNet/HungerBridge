package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class SystemMemoryHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;

    public SystemMemoryHandler(Config config, Logger logger) {
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
        if (!HttpUtil.checkAcl(ex, config, "system.memory")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access memory", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "system.memory")) return;

        var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeap = java.lang.management.ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long used = heap.getUsed();
        long total = heap.getCommitted();
        long max = heap.getMax();
        long free = Math.max(0L, total - used);
        long nonheapUsed = nonHeap.getUsed();
        long nonheapCommitted = nonHeap.getCommitted();
        long nonheapMax = nonHeap.getMax();
        JsonObject resp = Json.obj(
                "ok", true,
                "used_bytes", used,
                "total_bytes", total,
                "free_bytes", free,
                "max_bytes", max,
                "nonheap_used", nonheapUsed,
                "nonheap_committed", nonheapCommitted,
                "nonheap_max", nonheapMax
        );
        HttpUtil.writeJson(ex, 200, resp);
    }
}
