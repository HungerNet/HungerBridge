package com.hungerbridge.common.testing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hungerbridge.common.security.TokenManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Minimal HMAC-signing HTTP client used by the Java integration tests. Mirrors the
 * HungerBridgePy signing rules (protocol v1).
 */
public final class SignedHttp {

    public static final class Signed {
        public final String tokenId;
        public final String timestamp;
        public final String nonce;
        public final String signature;

        public Signed(String tokenId, String timestamp, String nonce, String signature) {
            this.tokenId = tokenId;
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.signature = signature;
        }
    }

    public static final class Result {
        public final int status;
        public final String body;
        public final HttpResponse<String> raw;

        Result(HttpResponse<String> raw) {
            this.status = raw.statusCode();
            this.body = raw.body();
            this.raw = raw;
        }

        public JsonObject json() {
            JsonElement el = JsonParser.parseString(body);
            return el.getAsJsonObject();
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    // HTTP/1.1 like real clients (python-requests); the JDK client otherwise attempts an h2c upgrade.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final String tokenId;
    private final byte[] key;

    public SignedHttp(String base, String tokenId, String secretHex) {
        this.base = base;
        this.tokenId = tokenId;
        this.key = secretHex == null ? null : hex(secretHex);
    }

    public Signed sign(String method, String path, String canonicalBody, long epochSeconds, String nonce) {
        String ts = Long.toString(epochSeconds);
        String n = nonce != null ? nonce : UUID.randomUUID().toString().replace("-", "");
        String sts = TokenManager.canonicalRequest(method, path, ts, n, canonicalBody == null ? "" : canonicalBody);
        return new Signed(tokenId, ts, n, TokenManager.hmacHex(key, sts));
    }

    public Signed sign(String method, String path, String canonicalBody) {
        return sign(method, path, canonicalBody, Instant.now().getEpochSecond(), null);
    }

    public Result send(String method, String path, String body, Signed signed) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (signed != null) {
            b.header("X-Auth-Id", signed.tokenId)
                    .header("X-Auth-Timestamp", signed.timestamp)
                    .header("X-Auth-Nonce", signed.nonce)
                    .header("X-Auth-Signature", signed.signature);
        }
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }
        return new Result(http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    public Result get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, key == null ? null : sign("GET", path, ""));
    }

    /** POST with an already canonical JSON body (what HungerBridgePy sends). */
    public Result post(String path, String canonicalBody) throws IOException, InterruptedException {
        return send("POST", path, canonicalBody, sign("POST", path, canonicalBody));
    }

    public HttpClient client() {
        return http;
    }

    public String base() {
        return base;
    }

    public static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
