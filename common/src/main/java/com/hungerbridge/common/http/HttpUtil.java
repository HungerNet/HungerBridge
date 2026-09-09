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

    

    public static AuthResult verifyRequest(HttpExchange ex, Config config, String requiredPermissionNode) {
        AuthResult out = new AuthResult();
        try {
            // Attempt to obtain request JSON body if present
            Object o = ex.getAttribute("hb.request.json");
            String canonicalBodyStr = "";
            if (o instanceof com.google.gson.JsonObject) canonicalBodyStr = TokenManager.canonicalizeJson((com.google.gson.JsonObject) o);

            boolean ok = auth(ex, config, o instanceof com.google.gson.JsonObject ? (com.google.gson.JsonObject) o : null);
            if (!ok) {
                out.ok = false; out.reason = "unauthenticated"; return out;
            }

            String tokenId = (String) ex.getAttribute("hb.auth.tokenId");
            TokenManager.Token token = (TokenManager.Token) ex.getAttribute("hb.auth.token");
            out.ok = true; out.reason = "ok"; out.tokenId = tokenId; out.token = token; out.permission = requiredPermissionNode;

            // Check ACL
            if (requiredPermissionNode != null && !requiredPermissionNode.isBlank()) {
                if (!tokenAclAllows(token, requiredPermissionNode)) {
                    out.ok = false; out.reason = "forbidden"; return out;
                }
            }
            return out;
        } catch (Exception e) {
            out.ok = false; out.reason = "internal_error"; return out;
        }
    }

    public static boolean tokenAclAllows(TokenManager.Token token, String action) {
        if (token == null) return false;
        // Determine permission set: prefer policyId from token and runtime config
        java.util.List<String> perms = token.permissions;
        // If token has a policyId, attempt to load the configured permissions
        try {
            Config cfg = (Config) null; // placeholder
        } catch (Exception ignored) {}
        // Accept multiple candidate nodes: exactly action, and common prefixes
        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(action);
        if (!action.contains(".")) {
            candidates.add("server." + action);
            candidates.add("world." + action);
            candidates.add("system." + action);
            candidates.add("players." + action);
        }
        for (String c : candidates) {
            if (TokenManager.permissionMatches(c, perms)) return true;
        }
        return false;
    }

    public static java.util.List<String> mergedPermissions(TokenManager.Token token, Config config) {
        java.util.LinkedHashSet<String> perms = new java.util.LinkedHashSet<>();
        if (token != null && token.permissions != null) {
            for (String p : token.permissions) {
                if (p != null && !p.isBlank()) perms.add(p.trim());
            }
        }
        if (token != null && token.policyId != null && config != null && config.getTokensConfig() != null) {
            var pol = config.getTokensConfig().getPolicy(token.policyId);
            if (pol != null) {
                for (String p : pol.permissions) {
                    if (p != null && !p.isBlank()) perms.add(p.trim());
                }
            }
        }
        return new java.util.ArrayList<>(perms);
    }

    public static boolean checkAcl(HttpExchange ex, Config config, String action) {
        TokenManager.Token token = (TokenManager.Token) ex.getAttribute("hb.auth.token");
        if (token == null) return false;
        java.util.List<String> perms = mergedPermissions(token, config);

        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(action);
        if (!action.contains(".")) {
            candidates.add("server." + action);
            candidates.add("world." + action);
            candidates.add("system." + action);
            candidates.add("players." + action);
        }
        for (String c : candidates) {
            if (TokenManager.permissionMatches(c, perms)) return true;
        }
        return false;
    }

    /**
     * New HKIM/HMAC auth entrypoint.
     *
     * NOTE: This method does NOT read the request body. The caller (handler)
     * must pass the already-parsed canonical JSON body as `canonicalBody`.
     */
    public static boolean auth(HttpExchange ex, Config config, com.google.gson.JsonObject canonicalBody) {
        if (config == null || config.getTokenManager() == null) return false;
        TokenManager tm = config.getTokenManager();

        String tokenId = ex.getRequestHeaders().getFirst("X-Auth-Id");
        String timestamp = ex.getRequestHeaders().getFirst("X-Auth-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Auth-Nonce");
        String signature = ex.getRequestHeaders().getFirst("X-Auth-Signature");

        if (tokenId == null || tokenId.isBlank() || signature == null) return false;

        // Build canonical body string from provided JsonObject. If null or empty, use empty string.
        String canonicalBodyStr = "";
        if (canonicalBody != null) canonicalBodyStr = TokenManager.canonicalizeJson(canonicalBody);

        TokenManager.VerifyResult vr = tm.verifyHmacDetailed(tokenId, timestamp, nonce, signature, ex.getRequestMethod(), ex.getRequestURI().getPath(), canonicalBodyStr, 0L);
        if (vr != TokenManager.VerifyResult.OK) {
            return false;
        }

        TokenManager.Token token = tm.listTokens().get(tokenId);
        if (token == null) return false;
        if (token.revoked) return false;

        // attach token info for downstream handlers
        ex.setAttribute("hb.auth.tokenId", tokenId);
        ex.setAttribute("hb.auth.token", token);
        return true;
    }

    /**
     * Backwards-compatible wrapper that obtains a pre-parsed JSON object from the
     * request attributes and delegates to the new auth method. This wrapper does
     * NOT read the request body from the stream.
     */
    public static boolean auth(HttpExchange ex, Config config) {
        Object o = ex.getAttribute("hb.request.json");
        if (o instanceof com.google.gson.JsonObject) {
            return auth(ex, config, (com.google.gson.JsonObject) o);
        }
        // If pre-parsed JSON is not available, do not attempt to read the body here.
        return false;
    }

    public static boolean rateLimit(HttpExchange ex, Config config, String action) throws IOException {
        // Rate limiting removed; always allow.
        return true;
    }

    public static JsonObject readJson(HttpExchange ex) throws IOException {
        String cached = (String) ex.getAttribute("hb.request.body");
        if (cached != null) {
            if (cached.isEmpty()) return null;
            com.google.gson.JsonObject parsed = JsonParser.parseString(cached).getAsJsonObject();
            ex.setAttribute("hb.request.json", parsed);
            return parsed;
        }
        try (InputStream in = ex.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) return null;
            ex.setAttribute("hb.request.body", body);
            com.google.gson.JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
            ex.setAttribute("hb.request.json", parsed);
            return parsed;
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
