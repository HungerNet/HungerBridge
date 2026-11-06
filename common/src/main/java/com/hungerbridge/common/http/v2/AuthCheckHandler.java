package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * GET /auth/check — identity and <i>effective</i> permissions of the calling token
 * (token + policy permissions, legacy aliases expanded). Available to every valid token;
 * failures are regular 401 responses with a {@code reason}.
 */
public final class AuthCheckHandler extends ApiHandler {

    public AuthCheckHandler(Config config, Logger logger) {
        super(config, logger, "/auth/check", "GET", null);
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        TokenManager.Token t = HttpUtil.authToken(ex);
        JsonArray perms = new JsonArray();
        for (String p : HttpUtil.mergedPermissions(t, config)) perms.add(p);
        JsonObject resp = Json.obj("ok", true, "tokenId", t.id, "policyId", t.policyId, "permissions", perms);
        resp.addProperty("expiry", t.expiry);
        HttpUtil.writeJson(ex, 200, resp);
    }
}
