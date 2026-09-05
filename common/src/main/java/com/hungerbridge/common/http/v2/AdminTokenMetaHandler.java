package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

public final class AdminTokenMetaHandler implements HttpHandler {
    private final AdminService admin;
    private final Config config;

    public AdminTokenMetaHandler(AdminService admin, Config config) {
        this.admin = admin;
        this.config = config;
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
        if (!HttpUtil.checkAcl(ex, config, "admin.token.meta")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to inspect token metadata", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "admin.token.meta")) return;

        JsonArray arr = new JsonArray();
        Map<String, TokenManager.Token> tokens = admin.listTokens();
        for (Map.Entry<String, TokenManager.Token> entry : tokens.entrySet()) {
            TokenManager.Token t = entry.getValue();
            JsonObject obj = Json.obj("id", t.id, "policyId", t.policyId, "revoked", t.revoked, "expiry", t.expiry);
            arr.add(obj);
        }
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "tokens", arr));
    }
}
