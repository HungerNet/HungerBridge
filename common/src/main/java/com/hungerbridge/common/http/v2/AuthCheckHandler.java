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
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }

        Object tok = ex.getAttribute("hb.auth.token");
        if (!(tok instanceof TokenManager.Token)) {
            HttpUtil.error(ex, 500, "server_error", "token metadata unavailable", config);
            return;
        }
        TokenManager.Token t = (TokenManager.Token) tok;

        JsonObject out = new JsonObject();
        out.addProperty("id", t.id);
        out.addProperty("policyId", t.policyId);
        out.addProperty("revoked", t.revoked);
        out.addProperty("expiry", t.expiry);
        if (t.whitelist != null) {
            JsonArray wa = new JsonArray();
            for (String s : t.whitelist) wa.add(s);
            out.add("whitelist", wa);
        }
        if (t.blacklist != null) {
            JsonArray ba = new JsonArray();
            for (String s : t.blacklist) ba.add(s);
            out.add("blacklist", ba);
        }

        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "permissions", out));
    }
}
