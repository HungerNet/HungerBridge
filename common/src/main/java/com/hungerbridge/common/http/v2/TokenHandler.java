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
 * /tokens
 * - GET: list tokens (requires authenticated admin token)
 * - POST: create token (requires authenticated admin token)
 * - DELETE /tokens/{id}: revoke token (requires authenticated admin token)
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
        if (!HttpUtil.checkAcl(ex, config, "admin")) {
            HttpUtil.error(ex, 403, "forbidden", "Admin rights required", config);
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
            List<String> whitelist = null;
            List<String> blacklist = null;
            if (body != null) {
                if (body.has("expiry")) expiry = body.get("expiry").getAsLong();
                if (body.has("whitelist")) {
                    whitelist = new ArrayList<>();
                    for (var el : body.getAsJsonArray("whitelist")) whitelist.add(el.getAsString());
                }
                if (body.has("blacklist")) {
                    blacklist = new ArrayList<>();
                    for (var el : body.getAsJsonArray("blacklist")) blacklist.add(el.getAsString());
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

                // create token and return its id and plaintext secret
                TokenManager.IssueResult res = tm.issueTokenWithPickup(tokenId, expiry, whitelist, blacklist, 300);
                if (res == null) { HttpUtil.error(ex, 500, "create_failed", "failed to create token", config); return; }
                // consume the pickup immediately to obtain the secret
                TokenManager.PickupRecord pr = tm.consumePickup(res.pickupId);
                if (pr == null) { HttpUtil.error(ex, 500, "create_failed", "failed to retrieve token secret", config); return; }
                if (policyId != null && !policyId.isBlank()) tm.setTokenPolicyId(res.tokenId, policyId);
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
                    "policyId", t.policyId,
                    "revoked", t.revoked,
                    "expiry", t.expiry
                );
            if (t.whitelist != null) {
                JsonArray wa = new JsonArray();
                for (String s : t.whitelist) wa.add(s);
                o.add("whitelist", wa);
            }
            if (t.blacklist != null) {
                JsonArray ba = new JsonArray();
                for (String s : t.blacklist) ba.add(s);
                o.add("blacklist", ba);
            }
            arr.add(o);
        }
        JsonObject resp = Json.obj("ok", true);
        resp.add("tokens", arr);
        HttpUtil.writeJson(ex, 200, resp);
    }
}
