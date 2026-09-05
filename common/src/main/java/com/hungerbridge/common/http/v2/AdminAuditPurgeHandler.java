package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class AdminAuditPurgeHandler implements HttpHandler {
    private final AdminService admin;
    private final Config config;

    public AdminAuditPurgeHandler(AdminService admin, Config config) {
        this.admin = admin;
        this.config = config;
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
        if (!HttpUtil.checkAcl(ex, config, "admin.audit.purge")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to purge audit logs", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "admin.audit.purge")) return;

        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "purged", true));
    }
}
