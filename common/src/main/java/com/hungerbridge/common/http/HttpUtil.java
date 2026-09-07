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
        AuthResult r = verifyRequest(ex, config, null);
        if (r == null) return false;
        if (!r.ok) return false;
        // attach token metadata
        ex.setAttribute("hb.auth.tokenId", r.tokenId);
        if (r.token != null) ex.setAttribute("hb.auth.token", r.token);
        return true;
    }

    public static final class AuthResult {
        public boolean ok;
        public String reason; // e.g., no_token, bad_signature, revoked, expired, denied_by_policy
        public String tokenId;
        public TokenManager.Token token;
    }

    /**
     * Unified request verification and ACL enforcement.
     * If requiredScope is null, only signature/nonce/timestamp checks are applied.
     */
    public static AuthResult verifyRequest(HttpExchange ex, Config config, String requiredScope) {
        AuthResult out = new AuthResult();
        TokenManager tm = config.getTokenManager();
        if (tm == null) {
            out.ok = false; out.reason = "no_token_manager"; return out;
        }

        String tokenId = ex.getRequestHeaders().getFirst("X-Auth-Token-Id");
        String ts = ex.getRequestHeaders().getFirst("X-Auth-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Auth-Nonce");
        String sig = ex.getRequestHeaders().getFirst("X-Auth-Signature");

        // Read and cache request body
        String bodyStr = (String) ex.getAttribute("hb.request.body");
        if (bodyStr == null) {
            try (InputStream in = ex.getRequestBody()) {
                byte[] b = in.readAllBytes();
                bodyStr = new String(b, StandardCharsets.UTF_8).trim();
                if (bodyStr.isEmpty()) bodyStr = "";
                ex.setAttribute("hb.request.body", bodyStr);
            } catch (IOException e) {
                out.ok = false; out.reason = "internal_error"; return out;
            }
        }

        int allowedSkew = 300;
        try {
            com.hungerbridge.common.TokensConfig tc = config.getTokensConfig();
            if (tc != null) {
                TokenManager.Token runtimeToken = tm.listTokens().get(tokenId);
                com.hungerbridge.common.TokensConfig.TokenPolicy policy = null;
                if (runtimeToken != null && runtimeToken.policyId != null && !runtimeToken.policyId.isBlank()) policy = tc.getPolicy(runtimeToken.policyId);
                if (policy == null) policy = tc.getPolicy(tokenId);
                if (policy != null) allowedSkew = policy.maxSkewSeconds; else allowedSkew = tc.maxSkewSeconds;
                if (allowedSkew < 0) allowedSkew = Integer.MAX_VALUE;
            }
        } catch (Exception ignored) {}

        TokenManager.VerifyResult vr = tm.verifyHmacDetailed(tokenId, ts, nonce, sig, ex.getRequestMethod(), ex.getRequestURI().getPath(), bodyStr, allowedSkew);
        if (vr != TokenManager.VerifyResult.OK) {
            out.ok = false;
            switch (vr) {
                case NO_TOKEN: out.reason = "no_token"; break;
                case REVOKED: out.reason = "revoked"; break;
                case EXPIRED: out.reason = "expired"; break;
                case BAD_TIMESTAMP: out.reason = "bad_timestamp"; break;
                case NONCE_REPLAY: out.reason = "nonce_replay"; break;
                case BAD_SIGNATURE: out.reason = "bad_signature"; break;
                default: out.reason = "internal_error"; break;
            }
            return out;
        }

        // signature OK; attach token
        TokenManager.Token tk = tm.listTokens().get(tokenId);
        out.ok = true; out.reason = "ok"; out.tokenId = tokenId; out.token = tk;

        // If a requiredScope is provided, evaluate ACLs
        if (requiredScope != null) {
            boolean allowed = true;
            if (tk != null) {
                allowed = tokenAclAllows(tk, requiredScope);
            }
            if (!allowed) {
                // consult policy fallback
                boolean allowedByPolicy = false;
                try {
                    com.hungerbridge.common.TokensConfig tc = config.getTokensConfig();
                    if (tc != null) {
                        com.hungerbridge.common.TokensConfig.TokenPolicy policy = null;
                        if (tk != null && tk.policyId != null && !tk.policyId.isBlank()) policy = tc.getPolicy(tk.policyId);
                        if (policy == null) policy = tc.getPolicy(tokenId);
                        if (policy != null) {
                            if (policy.endpoints != null) {
                                if (policy.endpoints.isEmpty()) {
                                    if ("whitelist".equalsIgnoreCase(policy.endpointsMode)) allowedByPolicy = false; else allowedByPolicy = true;
                                } else {
                                    if ("whitelist".equalsIgnoreCase(policy.endpointsMode)) allowedByPolicy = policy.endpoints.contains(requiredScope);
                                    else allowedByPolicy = !policy.endpoints.contains(requiredScope);
                                }
                            } else allowedByPolicy = true;
                        }
                    }
                } catch (Exception ignored) {}
                if (!allowedByPolicy) {
                    out.ok = false; out.reason = "denied_by_policy"; return out;
                }
            }
        }

        return out;
    }

    public static boolean checkAcl(HttpExchange ex, Config config, String action) {
        String ip = ex.getRemoteAddress() != null ? ex.getRemoteAddress().getAddress().getHostAddress() : null;

        Object tokObj = ex.getAttribute("hb.auth.token");
        if (!(tokObj instanceof TokenManager.Token)) {
            // no token metadata available — deny by default
            com.hungerbridge.common.log.AuditLogger alx = config != null ? config.getAuditLogger() : null;
            String tidx = (String) ex.getAttribute("hb.auth.tokenId");
            if (alx != null) {
                java.util.Map<String,Object> extra = new java.util.HashMap<>();
                extra.put("path", ex.getRequestURI().getPath());
                extra.put("method", ex.getRequestMethod());
                alx.logEvent(tidx, ip, action, "denied", extra);
            }
            return false;
        }
        TokenManager.Token tk = (TokenManager.Token) tokObj;
        // First, evaluate explicit runtime token lists if present
        if (tokenAclAllows(tk, action)) {
            // allowed by runtime lists
        } else {
            // If runtime lists denied, consult tokens.yaml policy (if any)
            boolean allowedByPolicy = false;
            try {
                com.hungerbridge.common.TokensConfig tc = config != null ? config.getTokensConfig() : null;
                if (tc != null) {
                    com.hungerbridge.common.TokensConfig.TokenPolicy policy = null;
                    if (tk.policyId != null && !tk.policyId.isBlank()) policy = tc.getPolicy(tk.policyId);
                    if (policy == null) policy = tc.getPolicy(tk.id);
                    if (policy != null) {
                        // apply same semantics as tokenAclAllows but using policy lists
                        if (policy.endpoints != null) {
                            if (policy.endpoints.isEmpty()) {
                                // empty whitelist/blacklist semantics determined by endpointsMode
                                if ("whitelist".equalsIgnoreCase(policy.endpointsMode)) {
                                    allowedByPolicy = false; // empty whitelist => deny all
                                } else {
                                    allowedByPolicy = true; // empty blacklist => allow all
                                }
                            } else {
                                if ("whitelist".equalsIgnoreCase(policy.endpointsMode)) {
                                    allowedByPolicy = policy.endpoints.contains(action);
                                } else {
                                    allowedByPolicy = !policy.endpoints.contains(action);
                                }
                            }
                        } else {
                            // no explicit endpoints listed in policy -> allow
                            allowedByPolicy = true;
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (!allowedByPolicy) {
                com.hungerbridge.common.log.AuditLogger al = config != null ? config.getAuditLogger() : null;
                String tid = (String) ex.getAttribute("hb.auth.tokenId");
                if (al != null) {
                    java.util.Map<String,Object> extra = new java.util.HashMap<>();
                    extra.put("path", ex.getRequestURI().getPath());
                    extra.put("method", ex.getRequestMethod());
                    al.logEvent(tid, ip, action, "denied", extra);
                }
                return false;
            }
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

    public static boolean tokenAclAllows(TokenManager.Token tk, String action) {
        if (tk == null) return false;
        if (tk.revoked) return false;
        if (tk.expiry > 0 && Instant.now().getEpochSecond() > tk.expiry) return false;
        // Unified semantics using `list` + `listMode`.
        // - If a list is present and mode is "whitelist": empty => deny all, otherwise only listed actions allowed.
        // - If a list is present and mode is "blacklist": empty => allow all, otherwise listed actions are denied.
        // - If no list present: allow.
        if (tk.list != null) {
            String mode = tk.listMode == null ? "blacklist" : tk.listMode;
            if ("whitelist".equalsIgnoreCase(mode)) {
                if (tk.list.isEmpty()) return false;
                return tk.list.contains(action);
            } else {
                if (tk.list.isEmpty()) return true;
                return !tk.list.contains(action);
            }
        }
        return true;
    }

    private static String canonicalizeJson(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return "null";
        if (el.isJsonPrimitive()) return el.toString();
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
                if (!first) sb.append(',');
                sb.append(canonicalizeJson(e));
                first = false;
            }
            sb.append(']');
            return sb.toString();
        }
        // object: sort keys lexicographically
        java.util.Map<String, com.google.gson.JsonElement> map = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> en : el.getAsJsonObject().entrySet()) {
            map.put(en.getKey(), en.getValue());
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> en : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append(Json.GSON.toJson(en.getKey()));
            sb.append(':');
            sb.append(canonicalizeJson(en.getValue()));
            first = false;
        }
        sb.append('}');
        return sb.toString();
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

        // Do not log "allowed" here; handlers should log final allowed/denied
        // after completing request processing to ensure accurate results.
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
        boolean isHead = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(status, isHead ? 0 : bytes.length);
        if (!isHead) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            // For HEAD requests, do not write a body — just close the stream to complete the exchange.
            try {
                ex.getResponseBody().close();
            } catch (Exception ignored) {}
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
