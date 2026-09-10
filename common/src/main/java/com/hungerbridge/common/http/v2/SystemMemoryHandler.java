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

        long heapUsed = Math.max(0L, heap.getUsed());
        long heapCommitted = Math.max(0L, heap.getCommitted());
        long heapMax = Math.max(0L, heap.getMax());
        long heapFree = Math.max(0L, heapCommitted - heapUsed);

        long nonheapUsed = Math.max(0L, nonHeap.getUsed());
        long nonheapCommitted = Math.max(0L, nonHeap.getCommitted());
        long nonheapMaxRaw = nonHeap.getMax();
        Long nonheapMax = nonheapMaxRaw >= 0L ? Math.max(0L, nonheapMaxRaw) : null;

        long jvmUsed = heapUsed + nonheapUsed;
        long jvmCommitted = heapCommitted + nonheapCommitted;
        long jvmMax = heapMax + (nonheapMax == null ? 0L : nonheapMax);
        if (nonheapMax == null) {
            jvmMax = heapMax;
        }

        long processUsed = 0L;
        long processVirtual = 0L;
        try {
            var provider = com.hungerbridge.common.ProcessMemoryProviderFactory.create();
            processUsed = Math.max(0L, provider.getProcessUsedBytes());
            processVirtual = Math.max(0L, provider.getProcessVirtualBytes());
        } catch (Exception ignored) {
            processUsed = 0L;
            processVirtual = 0L;
        }

        JsonObject resp = Json.obj(
                "ok", true,
                "heap_used_bytes", heapUsed,
                "heap_committed_bytes", heapCommitted,
                "heap_max_bytes", heapMax,
                "nonheap_used_bytes", nonheapUsed,
                "nonheap_committed_bytes", nonheapCommitted,
                "nonheap_max_bytes", nonheapMax,
                "jvm_used_bytes", jvmUsed,
                "jvm_committed_bytes", jvmCommitted,
                "jvm_max_bytes", jvmMax,
                "process_used_bytes", processUsed,
                "process_virtual_bytes", processVirtual,
                "used_bytes", heapUsed,
                "total_bytes", heapCommitted,
                "free_bytes", heapFree,
                "max_bytes", heapMax
        );
        HttpUtil.writeJson(ex, 200, resp);
    }
}
