package com.hungerbridge.common.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * Base class for authenticated JSON endpoints. Enforces, in order:
 * exact path → HTTP method → body size/JSON shape → rate limit + HMAC auth (once) →
 * permission node → {@link #handleAuthorized}. Any uncaught exception becomes a JSON
 * 500 instead of a dropped connection.
 */
public abstract class ApiHandler implements HttpHandler {

    protected final Config config;
    protected final Logger logger;
    private final String path;
    private final String method;
    private final String permission;

    /**
     * @param permission permission node required, or {@code null} for "any valid token"
     */
    protected ApiHandler(Config config, Logger logger, String path, String method, String permission) {
        this.config = config;
        this.logger = logger;
        this.path = path;
        this.method = method;
        this.permission = permission;
    }

    public String path() {
        return path;
    }

    @Override
    public final void handle(HttpExchange ex) throws IOException {
        try {
            String requested = TokenManager.normalizePath(ex.getRequestURI().getPath());
            if (!requested.equals(path)) {
                HttpUtil.error(ex, 404, "not_found", "Unknown endpoint: " + requested, config);
                return;
            }
            if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", method);
                HttpUtil.error(ex, 405, "method_not_allowed", "Use " + method, config);
                return;
            }
            JsonObject body = null;
            if (!"GET".equals(method)) {
                try {
                    body = HttpUtil.readJson(ex);
                } catch (HttpUtil.PayloadTooLargeException e) {
                    HttpUtil.error(ex, 413, "request_too_large", e.getMessage(), config);
                    return;
                }
            }
            HttpUtil.AuthResult auth = HttpUtil.authenticate(ex, config, body);
            if (!auth.ok) {
                HttpUtil.writeAuthFailure(ex, auth);
                return;
            }
            if (permission != null && !HttpUtil.checkAcl(ex, config, permission)) {
                HttpUtil.error(ex, 403, "forbidden", "Token lacks permission '" + permission + "'", config);
                return;
            }
            handleAuthorized(ex, body == null ? new JsonObject() : body);
        } catch (HttpUtil.BadRequestException e) {
            HttpUtil.error(ex, 400, "bad_request", e.getMessage(), config);
        } catch (Exception e) {
            if (logger != null) logger.log("ERROR", "Unhandled error in " + path + ": " + e);
            if (!HttpUtil.hasResponded(ex)) {
                HttpUtil.error(ex, 500, "internal_error", "Internal server error", config);
            } else {
                ex.close();
            }
        }
    }

    protected abstract void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException;

    // --- strict field accessors (wrong type → 400) -----------------------------------------

    protected static String requireString(JsonObject body, String field) throws HttpUtil.BadRequestException {
        String value = optString(body, field, null);
        if (value == null || value.isBlank()) {
            throw new HttpUtil.BadRequestException("Missing field: " + field);
        }
        return value;
    }

    protected static String optString(JsonObject body, String field, String fallback) throws HttpUtil.BadRequestException {
        if (body == null || !body.has(field)) return fallback;
        JsonElement el = body.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new HttpUtil.BadRequestException("Field '" + field + "' must be a string");
        }
        return el.getAsString();
    }

    protected static boolean optBoolean(JsonObject body, String field, boolean fallback) throws HttpUtil.BadRequestException {
        if (body == null || !body.has(field)) return fallback;
        JsonElement el = body.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw new HttpUtil.BadRequestException("Field '" + field + "' must be a boolean");
        }
        return el.getAsBoolean();
    }
}
