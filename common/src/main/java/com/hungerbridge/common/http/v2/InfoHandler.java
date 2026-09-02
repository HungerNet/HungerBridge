package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * /info
 * GET, requires X-Auth-Key
 * Returns bridge metadata
 */
public final class InfoHandler implements HttpHandler {

    private final Config config;
    private final Logger logger;

    public InfoHandler(Config config, Logger logger) {
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
            HttpUtil.error(ex, 401, "unauthorized", "Invalid X-Auth-Key", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "info")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access info", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "info")) return;

        JsonObject bridge = Json.obj(
                "version", config.getVersion(),
                "platform", config.getPlatform(),
                "minecraft", config.getMinecraftVersion()
        );

        JsonObject resp = Json.obj(
                "ok", true,
                "bridge", bridge
        );

        HttpUtil.writeJson(ex, 200, resp);
    }
}
