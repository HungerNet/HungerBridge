package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
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
 * /v2/tokens
 * - GET: list tokens (requires root X-Auth-Key)
 * - POST: create token (requires root X-Auth-Key)
 * - DELETE /v2/tokens/{id}: revoke token (requires root X-Auth-Key)
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
        // Require root X-Auth-Key for management operations
        String rootKey = ex.getRequestHeaders().getFirst("X-Auth-Key");
        if (rootKey == null || !rootKey.equals(config.getAuthKey())) {
            HttpUtil.error(ex, 401, "unauthorized", "Management endpoints require root X-Auth-Key", config);
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
            long ttl = 0;
            List<String> whitelist = null;
            List<String> blacklist = null;
            if (body != null) {
                if (body.has("ttl_seconds")) ttl = body.get("ttl_seconds").getAsLong();
                if (body.has("whitelist")) {
                    whitelist = new ArrayList<>();
                    for (var el : body.getAsJsonArray("whitelist")) whitelist.add(el.getAsString());
                }
                if (body.has("blacklist")) {
                    blacklist = new ArrayList<>();
                    for (var el : body.getAsJsonArray("blacklist")) blacklist.add(el.getAsString());
                }
            }

            TokenManager.Token tk = tm.createToken(ttl, whitelist, blacklist);
            JsonObject resp = Json.obj(
                    "ok", true,
                    "id", tk.id,
                    "secret", tk.secret,
                    "expiry", tk.expiry
            );
            HttpUtil.writeJson(ex, 200, resp);
            return;
        }

        if ("DELETE".equalsIgnoreCase(method)) {
            // path expected: /v2/tokens/{id}
            String[] parts = path.split("/");
            if (parts.length < 4) {
                HttpUtil.error(ex, 400, "bad_request", "Missing token id", config);
                return;
            }
            String id = parts[3];
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
