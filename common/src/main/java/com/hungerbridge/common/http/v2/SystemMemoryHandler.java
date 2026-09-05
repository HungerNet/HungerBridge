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

        Runtime rt = Runtime.getRuntime();
        JsonObject resp = Json.obj(
                "ok", true,
                "used_bytes", rt.totalMemory() - rt.freeMemory(),
                "total_bytes", rt.totalMemory(),
                "free_bytes", rt.freeMemory(),
                "max_bytes", rt.maxMemory()
        );
        HttpUtil.writeJson(ex, 200, resp);
    }
}
