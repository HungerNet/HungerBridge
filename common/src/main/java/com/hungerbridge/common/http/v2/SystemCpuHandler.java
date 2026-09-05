package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

public final class SystemCpuHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;

    public SystemCpuHandler(Config config, Logger logger) {
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
        if (!HttpUtil.checkAcl(ex, config, "system.cpu")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access CPU", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "system.cpu")) return;

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = -1.0;
        try {
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                load = sun.getProcessCpuLoad();
            }
        } catch (Exception ignored) {}

        JsonObject resp = Json.obj("ok", true, "cpu_load", load, "processors", Runtime.getRuntime().availableProcessors());
        HttpUtil.writeJson(ex, 200, resp);
    }
}
