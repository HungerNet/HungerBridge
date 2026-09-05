package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class AdminConfigUpdateHandler implements HttpHandler {
    private final AdminService admin;
    private final Config config;
    private final String which;

    public AdminConfigUpdateHandler(AdminService admin, Config config, String which) {
        this.admin = admin;
        this.config = config;
        this.which = which;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtil.error(ex, 405, "method_not_allowed", "Use POST", config);
            return;
        }
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "admin.config.update")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to update config", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "admin.config.update")) return;

        JsonObject payload = HttpUtil.readJson(ex);
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "section", which, "updated", payload != null));
    }
}
