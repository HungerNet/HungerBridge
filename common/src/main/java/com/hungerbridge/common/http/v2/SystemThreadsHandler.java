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
import java.lang.management.ThreadMXBean;

public final class SystemThreadsHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;

    public SystemThreadsHandler(Config config, Logger logger) {
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
        if (!HttpUtil.checkAcl(ex, config, "system.threads")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access threads", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "system.threads")) return;

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        JsonObject resp = Json.obj(
                "ok", true,
                "current", threads.getThreadCount(),
                "peak", threads.getPeakThreadCount(),
                "daemon", threads.getDaemonThreadCount()
        );
        HttpUtil.writeJson(ex, 200, resp);
    }
}
