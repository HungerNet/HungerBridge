package com.hungerbridge.common.http.v2;

import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class PickupHandler implements HttpHandler {

    private final Config config;

    public PickupHandler(Config config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            HttpUtil.error(ex, 405, "method_not_allowed", "Use GET", config);
            return;
        }

        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            HttpUtil.error(ex, 404, "not_found", "Pickup id missing", config);
            return;
        }
        String pickupId = parts[parts.length - 1];

        TokenManager tm = config.getTokenManager();
        if (tm == null) {
            HttpUtil.error(ex, 500, "server_error", "Token manager not initialized", config);
            return;
        }

        TokenManager.PickupRecord pr = tm.consumePickup(pickupId);
        if (pr == null) {
            HttpUtil.error(ex, 404, "not_found", "Pickup not found or expired", config);
            return;
        }

        // return token id and plaintext secret once
        var resp = Json.obj(
                "ok", true,
                "token_id", pr.tokenId,
                "token_secret", pr.secret
        );
        HttpUtil.writeJson(ex, 200, resp);
    }
}
