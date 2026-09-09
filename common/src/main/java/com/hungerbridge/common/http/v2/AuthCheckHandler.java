package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /auth/check
 * - Shows the permissions the API token has. Always allowed for any valid token.
 */
public final class AuthCheckHandler implements HttpHandler {

    private final Config config;

    public AuthCheckHandler(Config config) { this.config = config; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Perform a detailed verification to return precise error reasons
        TokenManager tm = config.getTokenManager();
        String tokenId = ex.getRequestHeaders().getFirst("X-Auth-Id");
        String ts = ex.getRequestHeaders().getFirst("X-Auth-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Auth-Nonce");
        String sig = ex.getRequestHeaders().getFirst("X-Auth-Signature");
        TokenManager.VerifyResult vr = tm.verifyHmacDetailed(tokenId, ts, nonce, sig, ex.getRequestMethod(), ex.getRequestURI().getPath(), "", 0L);
        if (vr == TokenManager.VerifyResult.NO_TOKEN) {
            HttpUtil.writeJson(ex, 200, Json.obj("ok", false, "error", "unauthenticated"));
            return;
        }
        if (vr == TokenManager.VerifyResult.REVOKED) {
            HttpUtil.writeJson(ex, 200, Json.obj("ok", false, "error", "revoked"));
            return;
        }
        if (vr != TokenManager.VerifyResult.OK) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }

        TokenManager.Token t = tm.listTokens().get(tokenId);
        if (t == null) { HttpUtil.writeJson(ex, 200, Json.obj("ok", false, "error", "unauthenticated")); return; }

        // auth.check is intentionally always enabled for any valid token; it does not require
        // a dedicated permission node, and should not be blocked by ACL checks.

        // collect permissions from policy if present
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (t.policyId != null && config.getTokensConfig() != null) {
            var pol = config.getTokensConfig().getPolicy(t.policyId);
            if (pol != null) perms.addAll(pol.permissions);
        }
        if (perms.isEmpty() && t.permissions != null) perms.addAll(t.permissions);

        JsonArray pa = new JsonArray();
        for (String p : perms) pa.add(p);

        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "tokenId", t.id, "policyId", t.policyId, "permissions", pa));
    }
}
