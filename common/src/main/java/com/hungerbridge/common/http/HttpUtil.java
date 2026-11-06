package com.hungerbridge.common.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.security.Permissions;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request/response helpers shared by all endpoints: body parsing, HMAC authentication,
 * rate limiting, ACL checks and JSON responses.
 *
 * <p>Error bodies are {@code {"ok":false,"error":"<code>","message":"<text>"}} plus an
 * optional machine-readable {@code "reason"}.
 */
public final class HttpUtil {

    public static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    private HttpUtil() {}

    // ------------------------------------------------------------------------------------
    // Exceptions
    // ------------------------------------------------------------------------------------

    /** Malformed or invalid request → HTTP 400. */
    public static class BadRequestException extends IOException {
        public BadRequestException(String message) { super(message); }
    }

    /** Body larger than {@link #MAX_REQUEST_BODY_BYTES} → HTTP 413. */
    public static final class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) { super(message); }
    }

    // ------------------------------------------------------------------------------------
    // Rate limiting (token buckets keyed by IP / token / pickup)
    // ------------------------------------------------------------------------------------

    private static final class TokenBucket {
        final int burst;
        final double refillPerNano;
        double tokens;
        long lastRefillNanos;
        volatile long lastUsedNanos;

        TokenBucket(int burst, int rps) {
            this.burst = Math.max(1, burst);
            this.refillPerNano = Math.max(0.0, rps) / 1_000_000_000.0;
            this.tokens = this.burst;
            this.lastRefillNanos = System.nanoTime();
            this.lastUsedNanos = this.lastRefillNanos;
        }

        /** Returns 0 if a token was consumed, otherwise the seconds until one is available. */
        synchronized long tryConsume() {
            long now = System.nanoTime();
            lastUsedNanos = now;
            long elapsed = Math.max(0L, now - lastRefillNanos);
            tokens = Math.min(burst, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0L;
            }
            if (refillPerNano <= 0.0) return 60L;
            double nanos = (1.0 - tokens) / refillPerNano;
            return Math.max(1L, (long) Math.ceil(nanos / 1_000_000_000.0));
        }
    }

    private static final ConcurrentHashMap<String, TokenBucket> BUCKETS = new ConcurrentHashMap<>();
    private static final long BUCKET_IDLE_NANOS = 10L * 60L * 1_000_000_000L;
    private static final int BUCKET_SWEEP_THRESHOLD = 10_000;

    /** Seconds the caller must wait, or 0 if allowed. Consumes one token if allowed. */
    private static long consume(String kind, String id, TokenManager.BucketConfig cfg) {
        if (cfg == null || cfg.burst <= 0 || cfg.rps <= 0 || id == null) return 0L;
        if (BUCKETS.size() > BUCKET_SWEEP_THRESHOLD) {
            long now = System.nanoTime();
            BUCKETS.values().removeIf(b -> now - b.lastUsedNanos > BUCKET_IDLE_NANOS);
        }
        // settings are part of the key so a config reload takes effect immediately
        String key = kind + ':' + id + ':' + cfg.burst + '/' + cfg.rps;
        return BUCKETS.computeIfAbsent(key, k -> new TokenBucket(cfg.burst, cfg.rps)).tryConsume();
    }

    /** Clears all rate-limit state (tests, config reload). */
    public static void resetRateLimits() {
        BUCKETS.clear();
    }

    public static String remoteIp(HttpExchange ex) {
        return ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null
                ? ex.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    /**
     * Applies the per-IP, per-token and (for {@code /pickup}) pickup buckets exactly once
     * per request. Returns 0 if allowed, otherwise the Retry-After seconds.
     */
    public static long checkRateLimit(HttpExchange ex, Config config) {
        if (config == null || ex == null) return 0L;
        TokenManager.RateLimitSettings s = config.getRateLimitSettings();
        if (s == null) return 0L;
        String ip = remoteIp(ex);
        long wait = consume("ip", ip, s.perIp);
        String tokenId = ex.getRequestHeaders().getFirst("X-Auth-Id");
        if (wait == 0L && tokenId != null && !tokenId.isBlank()) {
            wait = consume("token", tokenId, s.perToken);
        }
        String path = ex.getRequestURI() != null ? ex.getRequestURI().getPath() : "";
        if (wait == 0L && path != null && path.startsWith("/pickup")) {
            wait = consume("pickup", ip, s.pickup);
        }
        return wait;
    }

    /**
     * Rate-limit check that writes the 429 response itself. Returns {@code true} if the
     * request may proceed.
     */
    public static boolean rateLimit(HttpExchange ex, Config config) throws IOException {
        long wait = checkRateLimit(ex, config);
        if (wait == 0L) return true;
        writeRateLimited(ex, wait);
        return false;
    }

    private static void writeRateLimited(HttpExchange ex, long retryAfterSeconds) throws IOException {
        ex.getResponseHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
        error(ex, 429, "rate_limited", "Rate limit exceeded; retry after " + retryAfterSeconds + "s", null, null);
    }

    // ------------------------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------------------------

    public static final class AuthResult {
        public boolean ok;
        public int status = 401;
        public String error = "unauthorized";
        public String reason;
        public long retryAfterSeconds;
        public String tokenId;
        public TokenManager.Token token;

        static AuthResult fail(int status, String error, String reason) {
            AuthResult r = new AuthResult();
            r.status = status;
            r.error = error;
            r.reason = reason;
            return r;
        }
    }

    /**
     * Rate-limits (once) and verifies the HMAC signature of the request. {@code body} is the
     * already parsed JSON body (null for GET). Does not write a response; see
     * {@link #writeAuthFailure}.
     *
     * <p>Rate limiting happens <i>before</i> signature verification, so a throttled
     * request never consumes its nonce and can be retried unchanged.
     */
    public static AuthResult authenticate(HttpExchange ex, Config config, JsonObject body) {
        long wait = checkRateLimit(ex, config);
        if (wait > 0L) {
            AuthResult r = AuthResult.fail(429, "rate_limited", null);
            r.retryAfterSeconds = wait;
            return r;
        }
        TokenManager tm = config == null ? null : config.getTokenManager();
        if (tm == null) return AuthResult.fail(503, "unavailable", "token_manager_unavailable");

        String tokenId = ex.getRequestHeaders().getFirst("X-Auth-Id");
        String timestamp = ex.getRequestHeaders().getFirst("X-Auth-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Auth-Nonce");
        String signature = ex.getRequestHeaders().getFirst("X-Auth-Signature");
        if (isBlank(tokenId) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return AuthResult.fail(401, "unauthorized", "missing_credentials");
        }

        String canonicalBody = body == null ? "" : TokenManager.canonicalizeJson(body);
        TokenManager.VerifyResult vr = tm.verifyHmacDetailed(tokenId, timestamp, nonce, signature,
                ex.getRequestMethod(), ex.getRequestURI().getPath(), canonicalBody, 0L);
        switch (vr) {
            case OK:
                break;
            case BAD_TIMESTAMP:
                return AuthResult.fail(401, "unauthorized", "timestamp_out_of_range");
            case NONCE_REPLAY:
                return AuthResult.fail(401, "unauthorized", "nonce_replay");
            case REVOKED:
                return AuthResult.fail(401, "unauthorized", "token_revoked");
            case EXPIRED:
                return AuthResult.fail(401, "unauthorized", "token_expired");
            case INTERNAL_ERROR:
                return AuthResult.fail(500, "internal_error", null);
            default: // NO_TOKEN, BAD_SIGNATURE: indistinguishable on purpose
                return AuthResult.fail(401, "unauthorized", "invalid_credentials");
        }

        TokenManager.Token token = tm.listTokens().get(tokenId);
        if (token == null) return AuthResult.fail(401, "unauthorized", "invalid_credentials");
        RequestState state = RequestState.of(ex);
        state.tokenId = tokenId;
        state.token = token;
        AuthResult ok = new AuthResult();
        ok.ok = true;
        ok.status = 200;
        ok.error = null;
        ok.tokenId = tokenId;
        ok.token = token;
        return ok;
    }

    public static void writeAuthFailure(HttpExchange ex, AuthResult r) throws IOException {
        if (r.status == 429) {
            writeRateLimited(ex, r.retryAfterSeconds);
            return;
        }
        String message = r.status == 401 ? "Authentication required" : "Authentication unavailable";
        error(ex, r.status, r.error, message, null, r.reason);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------------------------

    /** Token permissions + policy permissions, with legacy aliases expanded. */
    public static List<String> mergedPermissions(TokenManager.Token token, Config config) {
        LinkedHashSet<String> perms = new LinkedHashSet<>();
        if (token != null && token.permissions != null) perms.addAll(token.permissions);
        if (token != null && token.policyId != null && config != null && config.getTokensConfig() != null) {
            var pol = config.getTokensConfig().getPolicy(token.policyId);
            if (pol != null) perms.addAll(pol.permissions);
        }
        return new ArrayList<>(Permissions.expand(perms));
    }

    /** Token authenticated for the current request (null before/without authentication). */
    public static TokenManager.Token authToken(HttpExchange ex) {
        return RequestState.of(ex).token;
    }

    public static String authTokenId(HttpExchange ex) {
        return RequestState.of(ex).tokenId;
    }

    public static boolean checkAcl(HttpExchange ex, Config config, String action) {
        TokenManager.Token token = authToken(ex);
        if (token == null) return false;
        // Fully-qualified nodes only (must contain a dot).
        if (action == null || action.isBlank() || !action.contains(".")) return false;
        return TokenManager.permissionMatches(action, mergedPermissions(token, config));
    }

    // ------------------------------------------------------------------------------------
    // Request / response bodies
    // ------------------------------------------------------------------------------------

    /**
     * Reads the request body as a JSON object (null if empty) and caches it on the exchange.
     *
     * @throws PayloadTooLargeException body over {@link #MAX_REQUEST_BODY_BYTES}
     * @throws BadRequestException      body is not a JSON object
     */
    public static JsonObject readJson(HttpExchange ex) throws IOException {
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BODY_BYTES) {
                    throw new PayloadTooLargeException("Request body exceeds " + MAX_REQUEST_BODY_BYTES + " bytes");
                }
                baos.write(buffer, 0, read);
            }
            bytes = baos.toByteArray();
        }
        String body = new String(bytes, StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) return null;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            throw new BadRequestException("Request body is not valid JSON");
        }
        if (!parsed.isJsonObject()) throw new BadRequestException("Request body must be a JSON object");
        JsonObject obj = parsed.getAsJsonObject();
        RequestState.of(ex).json = obj;
        return obj;
    }

    public static boolean hasResponded(HttpExchange ex) {
        return RequestState.of(ex).responded;
    }

    public static void markResponded(HttpExchange ex) {
        RequestState.of(ex).responded = true;
    }

    public static void writeJson(HttpExchange ex, int status, JsonObject body) throws IOException {
        if (hasResponded(ex)) return;
        markResponded(ex);
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            if (!head) out.write(bytes);
        }
    }

    public static void error(HttpExchange ex, int status, String err, String msg, Config config) throws IOException {
        error(ex, status, err, msg, config, null);
    }

    public static void error(HttpExchange ex, int status, String err, String msg, Config config, String reason) throws IOException {
        JsonObject body = Json.obj("ok", false, "error", err, "message", msg);
        if (reason != null) body.addProperty("reason", reason);
        writeJson(ex, status, body);
    }
}
