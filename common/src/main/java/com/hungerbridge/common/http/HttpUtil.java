package com.hungerbridge.common.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.hungerbridge.common.security.TokenManager;

/**
 * Small HTTP utilities shared by handlers.
 */
public final class HttpUtil {

    private HttpUtil() {}

    public static boolean auth(HttpExchange ex, Config config) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return false;

        String tokenId = ex.getRequestHeaders().getFirst("X-Auth-Token-Id");
        String ts = ex.getRequestHeaders().getFirst("X-Auth-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Auth-Nonce");
        String sig = ex.getRequestHeaders().getFirst("X-Auth-Signature");

        // Read and cache request body for signature verification
        String bodyStr = (String) ex.getAttribute("hb.request.body");
        if (bodyStr == null) {
            try (InputStream in = ex.getRequestBody()) {
                byte[] b = in.readAllBytes();
                bodyStr = new String(b, StandardCharsets.UTF_8).trim();
                if (bodyStr.isEmpty()) bodyStr = "";
                ex.setAttribute("hb.request.body", bodyStr);
            } catch (IOException e) {
                return false;
            }
        }

        int allowedSkew = 300;
        try {
            com.hungerbridge.common.TokensConfig tc = config.getTokensConfig();
            if (tc != null) {
                com.hungerbridge.common.TokensConfig.TokenPolicy policy = tc.getPolicy(tokenId);
                if (policy != null) allowedSkew = policy.maxSkewSeconds;
                else allowedSkew = tc.maxSkewSeconds;
                if (allowedSkew < 0) allowedSkew = Integer.MAX_VALUE;
            }
        } catch (Exception ignored) {}
        boolean ok = tm.verifyHmac(tokenId, ts, nonce, sig, ex.getRequestMethod(), ex.getRequestURI().getPath(), bodyStr, allowedSkew);
        if (!ok) return false;

        // attach token metadata for downstream ACL checks
        ex.setAttribute("hb.auth.tokenId", tokenId);
        TokenManager.Token tk = tm.listTokens().get(tokenId);
        if (tk != null) ex.setAttribute("hb.auth.token", tk);
        return true;
    }

    public static boolean checkAcl(HttpExchange ex, Config config, String action) {
        String ip = ex.getRemoteAddress() != null ? ex.getRemoteAddress().getAddress().getHostAddress() : null;

        Object tokObj = ex.getAttribute("hb.auth.token");
        if (!(tokObj instanceof TokenManager.Token)) {
            // no token metadata available — deny by default
            return false;
        }
        TokenManager.Token tk = (TokenManager.Token) tokObj;
        // revoked check
        if (tk.revoked) return false;
        // expiry check
        if (tk.expiry > 0 && Instant.now().getEpochSecond() > tk.expiry) return false;

        if (tk.whitelist != null && !tk.whitelist.isEmpty()) {
            return tk.whitelist.contains(action);
        }
        if (tk.blacklist != null && !tk.blacklist.isEmpty()) {
            return !tk.blacklist.contains(action);
        }
        // IP whitelist/blacklist enforcement (enforced after authentication)
        com.hungerbridge.common.security.SecurityConfig sc = config.getSecurityConfig();
        if (sc != null) {
            java.util.List<String> ipEntries = sc.ipList != null && !sc.ipList.isEmpty() ? sc.ipList : java.util.List.of();
            if (ipEntries.isEmpty() && (sc.ipWhitelist != null && !sc.ipWhitelist.isEmpty())) {
                ipEntries = sc.ipWhitelist;
            }
            if (!ipEntries.isEmpty()) {
                boolean matched = false;
                for (String pat : ipEntries) {
                    if (com.hungerbridge.common.security.IpMatcher.matches(pat, ip)) { matched = true; break; }
                }
                if ("whitelist".equalsIgnoreCase(sc.ipListMode)) {
                    if (!matched) return false;
                } else if (matched) {
                    return false;
                }
            }
            if (sc.ipBlacklist != null && !sc.ipBlacklist.isEmpty()) {
                for (String pat : sc.ipBlacklist) {
                    if (com.hungerbridge.common.security.IpMatcher.matches(pat, ip)) return false;
                }
            }
            if (sc.ipWhitelist != null && !sc.ipWhitelist.isEmpty() && "whitelist".equalsIgnoreCase(sc.ipListMode)) {
                boolean ok = false;
                for (String pat : sc.ipWhitelist) {
                    if (com.hungerbridge.common.security.IpMatcher.matches(pat, ip)) { ok = true; break; }
                }
                if (!ok) return false;
            }
        }
        return true;
    }

    public static boolean rateLimit(HttpExchange ex, Config config, String action) throws IOException {
        com.hungerbridge.common.security.RateLimiter rl = config.getRateLimiter();
        com.hungerbridge.common.log.AuditLogger al = config.getAuditLogger();
        String ip = ex.getRemoteAddress() != null ? ex.getRemoteAddress().getAddress().getHostAddress() : "unknown";

        String tokenId = (String) ex.getAttribute("hb.auth.tokenId");

        boolean tokenAllowed = true;
        boolean ipAllowed = true;
        if (rl != null) {
            tokenAllowed = rl.allowRequestForToken(tokenId);
            ipAllowed = rl.allowRequestForIp(ip);
        }

        if (!tokenAllowed || !ipAllowed) {
            if (al != null) {
                java.util.Map<String, Object> extra = new java.util.HashMap<>();
                extra.put("path", ex.getRequestURI().getPath());
                extra.put("method", ex.getRequestMethod());
                extra.put("reason", tokenAllowed ? "ip_rate_limited" : (ipAllowed ? "token_rate_limited" : "both_rate_limited"));
                al.logEvent(tokenId, ip, action, "rate_limited", extra);
            }
            HttpUtil.error(ex, 429, "rate_limited", "Rate limit exceeded", config);
            return false;
        }

        // allowed — log the allowed request for audit
        if (al != null) {
            java.util.Map<String, Object> extra = new java.util.HashMap<>();
            extra.put("path", ex.getRequestURI().getPath());
            extra.put("method", ex.getRequestMethod());
            al.logEvent(tokenId, ip, action, "allowed", extra);
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
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void error(HttpExchange ex, int status, String err, String msg, Config config) throws IOException {
        writeJson(ex, status, Json.obj(
                "ok", false,
                "error", err,
                "message", msg
        ));
    }
}
