package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;

public final class SystemDiskHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;

    public SystemDiskHandler(Config config, Logger logger) {
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
        if (!HttpUtil.checkAcl(ex, config, "system.disk")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access disk", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "system.disk")) return;

        File root = new File(".");
        long total = root.getTotalSpace();
        long free = root.getFreeSpace();
        JsonObject resp = Json.obj("ok", true, "total_bytes", total, "free_bytes", free, "used_bytes", total - free);
        HttpUtil.writeJson(ex, 200, resp);
    }
}
