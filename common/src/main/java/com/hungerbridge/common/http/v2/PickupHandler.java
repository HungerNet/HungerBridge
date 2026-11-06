package com.hungerbridge.common.http.v2;

import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /pickup/{id}?passkey={hex} — single-use retrieval of a newly created token secret.
 * Unauthenticated by design (the passkey is the credential); rate limited per IP.
 */
public final class PickupHandler implements HttpHandler {

    private final Config config;

    public PickupHandler(Config config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "GET");
                HttpUtil.error(ex, 405, "method_not_allowed", "Use GET", config);
                return;
            }
            if (!HttpUtil.rateLimit(ex, config)) return;

            String[] parts = TokenManager.normalizePath(ex.getRequestURI().getPath()).split("/");
            // ["", "pickup", "<id>"]
            if (parts.length != 3 || parts[2].isBlank()) {
                HttpUtil.error(ex, 404, "not_found", "Pickup id missing", config);
                return;
            }
            String pickupId = parts[2];
            String passkey = null;
            String query = ex.getRequestURI().getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && "passkey".equalsIgnoreCase(part.substring(0, eq))) {
                        passkey = part.substring(eq + 1);
                        break;
                    }
                }
            }
            if (passkey == null || passkey.isBlank()) {
                HttpUtil.error(ex, 401, "unauthorized", "Pickup passkey required", config, "missing_credentials");
                return;
            }
            TokenManager tm = config.getTokenManager();
            if (tm == null) {
                HttpUtil.error(ex, 503, "unavailable", "Token manager not initialized", config);
                return;
            }
            TokenManager.PickupRecord pr = tm.consumePickup(pickupId, passkey);
            if (pr == null) {
                HttpUtil.error(ex, 404, "not_found", "Pickup not found, expired, or invalid passkey", config);
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "token_id", pr.tokenId, "token_secret", pr.secret));
        } catch (RuntimeException e) {
            if (!HttpUtil.hasResponded(ex)) HttpUtil.error(ex, 500, "internal_error", "Internal server error", config);
        }
    }
}
