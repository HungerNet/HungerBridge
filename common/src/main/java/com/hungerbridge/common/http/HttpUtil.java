package com.hungerbridge.common.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public final class HttpUtil {

    private HttpUtil() {}

    public static final class AuthResult {
        public boolean ok;
        public String reason = "ok";
        public String tokenId;
        public String permission;
        public TokenManager.Token token;
    }

    public static boolean auth(HttpExchange ex, Config config) {
        AuthResult result = verifyRequest(ex, config, null);
        if (result == null || !result.ok) {
            return false;
        }
        ex.setAttribute("hb.auth.tokenId", result.tokenId);
        if (result.token != null) {
            ex.setAttribute("hb.auth.token", result.token);
        }
        return true;
    }

    public static AuthResult verifyRequest(HttpExchange ex, Config config, String requiredPermissionNode) {
        AuthResult out = new AuthResult();
        if (config == null || config.getTokenManager() == null) {
            out.ok = false;
            out.reason = "no_token";
            return out;
        }

        TokenManager tm = config.getTokenManager();
        String tokenId = ex.getRequestHeaders().getFirst("X-Auth-Token-Id");
        String timestamp = ex.getRequestHeaders().getFirst("X-Auth-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Auth-Nonce");
        String signature = ex.getRequestHeaders().getFirst("X-Auth-Signature");

        if (tokenId == null || tokenId.isBlank()) {
            out.ok = false;
            out.reason = "no_token";
            return out;
        }

        TokenManager.Token token = tm.listTokens().get(tokenId);
        if (token == null) {
            out.ok = false;
            out.reason = "no_token";
            return out;
        }

        out.tokenId = tokenId;
        out.token = token;
        out.permission = requiredPermissionNode;

        if (token.revoked) {
            out.ok = false;
            out.reason = "revoked";
            return out;
        }
        if (token.expiry != 0 && Instant.now().getEpochSecond() > token.expiry) {
            out.ok = false;
            out.reason = "expired";
            return out;
        }
        if (timestamp == null || nonce == null || signature == null) {
            out.ok = false;
            out.reason = "bad_signature";
            return out;
        }

        long skewLimit = token.maxSkew >= 0 ? token.maxSkew : 300L;
        long now = Instant.now().getEpochSecond();
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(now - ts) > skewLimit) {
                out.ok = false;
                out.reason = "skew_violation";
                return out;
            }
        } catch (NumberFormatException e) {
            out.ok = false;
            out.reason = "bad_signature";
            return out;
        }

        String body = (String) ex.getAttribute("hb.request.body");
        if (body == null) {
            try (InputStream in = ex.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                ex.setAttribute("hb.request.body", body);
            } catch (IOException e) {
                out.ok = false;
                out.reason = "bad_signature";
                return out;
            }
        }

        String canonical = TokenManager.canonicalRequest(
                ex.getRequestMethod(),
                TokenManager.normalizePath(ex.getRequestURI().getPath()),
                timestamp,
                nonce,
                body == null ? "" : body
        );
        byte[] secret = tm.deriveTokenSecret(token);
        String expected = TokenManager.hmacHex(secret, canonical);
        if (!expected.equalsIgnoreCase(signature)) {
            out.ok = false;
            out.reason = "bad_signature";
            return out;
        }

        if (requiredPermissionNode != null) {
            List<String> perms = token.permissions == null ? List.of() : token.permissions;
            if (!TokenManager.permissionMatches(requiredPermissionNode, perms)) {
                out.ok = false;
                out.reason = "denied_by_permissions";
                out.permission = requiredPermissionNode;
                return out;
            }
        }

        out.ok = true;
        out.reason = "ok";
        return out;
    }

    public static boolean tokenAclAllows(TokenManager.Token token, String action) {
        if (token == null || token.revoked) {
            return false;
        }
        if (token.expiry != 0 && Instant.now().getEpochSecond() > token.expiry) {
            return false;
        }
        if (token.permissions == null || token.permissions.isEmpty()) {
            return false;
        }
        return TokenManager.permissionMatches(action, token.permissions);
    }

    public static boolean checkAcl(HttpExchange ex, Config config, String action) {
        Object tokenObj = ex.getAttribute("hb.auth.token");
        if (!(tokenObj instanceof TokenManager.Token)) {
            return false;
        }
        TokenManager.Token token = (TokenManager.Token) tokenObj;
        return tokenAclAllows(token, action);
    }

    public static boolean rateLimit(HttpExchange ex, Config config, String action) throws IOException {
        if (config == null || config.getRateLimiter() == null) return true;
        String ip = ex.getRemoteAddress() != null ? ex.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        String tokenId = (String) ex.getAttribute("hb.auth.tokenId");
        if (!config.getRateLimiter().allowRequestForIp(ip)) {
            error(ex, 429, "rate_limited", "Rate limit exceeded", config);
            return false;
        }
        if (!config.getRateLimiter().allowRequestForToken(tokenId)) {
            error(ex, 429, "rate_limited", "Rate limit exceeded", config);
            return false;
        }
        return true;
    }

    public static JsonObject readJson(HttpExchange ex) throws IOException {
        String cached = (String) ex.getAttribute("hb.request.body");
        if (cached != null) {
            if (cached.isEmpty()) return null;
            return JsonParser.parseString(cached).getAsJsonObject();
        }
        try (InputStream in = ex.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) return null;
            ex.setAttribute("hb.request.body", body);
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    public static void writeJson(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? 0 : bytes.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    public static void error(HttpExchange ex, int status, String err, String msg, Config config) throws IOException {
        JsonObject body = Json.obj("ok", false, "error", err, "message", msg);
        writeJson(ex, status, body);
    }
}
