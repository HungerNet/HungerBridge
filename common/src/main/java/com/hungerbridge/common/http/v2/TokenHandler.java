package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.TokensConfig;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /tokens (legacy management endpoints)
 */
public final class TokenHandler implements HttpHandler {

    private final Config config;
    private final Logger logger;

    public TokenHandler(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }

        TokenManager tm = config.getTokenManager();
        if (tm == null) {
            HttpUtil.error(ex, 500, "server_error", "Token manager not initialized", config);
            return;
        }

        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("GET".equalsIgnoreCase(method)) {
            handleList(ex, tm);
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            JsonObject body = HttpUtil.readJson(ex);
            String policyId = body != null && body.has("policyId") ? body.get("policyId").getAsString() : null;
            String tokenId = body != null && body.has("tokenId") ? body.get("tokenId").getAsString() : null;
            long expiry = 0L;
            List<String> permissions = null;
            if (body != null) {
                if (body.has("expiry")) expiry = body.get("expiry").getAsLong();
                if (body.has("whitelist") || body.has("blacklist") || body.has("list") || body.has("list_mode")) {
                    HttpUtil.error(ex, 400, "legacy_fields", "legacy ACL list fields are unsupported; use permissions", config);
                    return;
                }
                if (body.has("permissions")) {
                    permissions = new ArrayList<>();
                    for (var el : body.getAsJsonArray("permissions")) permissions.add(el.getAsString());
                }
            }
            if (policyId == null || policyId.isBlank() || tokenId == null || tokenId.isBlank()) {
                HttpUtil.error(ex, 400, "bad_request", "policyId and tokenId required", config);
                return;
            }

            // validate policy exists
            TokensConfig tc = config.getTokensConfig();
            if (tc != null && !tc.hasPolicy(policyId)) {
                HttpUtil.error(ex, 400, "unknown_policy", "token policy id not found", config);
                return;
            }

            TokenManager.IssueResult res = tm.issueTokenWithPickup(tokenId, expiry, permissions, 300);
            if (res == null) { HttpUtil.error(ex, 500, "create_failed", "failed to create token", config); return; }
            TokenManager.PickupRecord pr = tm.consumePickup(res.pickupId);
            if (pr == null) { HttpUtil.error(ex, 500, "create_failed", "failed to retrieve token secret", config); return; }
            JsonObject resp = Json.obj(
                "ok", true,
                "id", res.tokenId,
                "secret", pr.secret,
                "expiry", expiry
            );
            HttpUtil.writeJson(ex, 200, resp);
            return;
        }

        if ("DELETE".equalsIgnoreCase(method)) {
            // path expected: /tokens/{id}
            String[] parts = path.split("/");
            if (parts.length < 3) {
                HttpUtil.error(ex, 400, "bad_request", "Missing token id", config);
                return;
            }
            String id = parts[2];
            boolean ok = tm.revokeToken(id);
            if (!ok) {
                HttpUtil.error(ex, 404, "not_found", "Token not found", config);
                return;
            }
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true));
            return;
        }

        HttpUtil.error(ex, 405, "method_not_allowed", "Use GET/POST/DELETE", config);
    }

    private void handleList(HttpExchange ex, TokenManager tm) throws IOException {
        Map<String, TokenManager.Token> map = tm.listTokens();
        JsonArray arr = new JsonArray();
        for (TokenManager.Token t : map.values()) {
                JsonObject o = Json.obj(
                    "id", t.id,
                    "revoked", t.revoked,
                    "expiry", t.expiry,
                    "max_skew", t.maxSkew
                );
            if (t.permissions != null && !t.permissions.isEmpty()) {
                JsonArray la = new JsonArray();
                for (String s : t.permissions) la.add(s);
                o.add("permissions", la);
            }
            arr.add(o);
        }
        JsonObject resp = Json.obj("ok", true);
        resp.add("tokens", arr);
        HttpUtil.writeJson(ex, 200, resp);
    }
}
