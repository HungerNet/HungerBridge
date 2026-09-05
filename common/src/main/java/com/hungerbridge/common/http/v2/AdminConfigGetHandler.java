package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class AdminConfigGetHandler implements HttpHandler {
    private final AdminService admin;
    private final Config config;
    private final String which;

    public AdminConfigGetHandler(AdminService admin, Config config, String which) {
        this.admin = admin;
        this.config = config;
        this.which = which;
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
        if (!HttpUtil.checkAcl(ex, config, "admin.config.get")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to get config", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "admin.config.get")) return;

        JsonObject resp = Json.obj("ok", true, "section", which, "value", Json.GSON.toJsonTree(config));
        HttpUtil.writeJson(ex, 200, resp);
    }
}
