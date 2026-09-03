package com.hungerbridge.common.security;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hungerbridge.common.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal token manager implementing HMAC verification, nonce replay protection,
 * and JSON-backed token storage. This is intentionally small and focussed on
 * server-side verification for the new token format "id:secret".
 */
public final class TokenManager {

    private final Path storageDir;
    private final Path tokensFile;
    private final Path sessionsFile;
    private final Logger logger;

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    // nonce -> expiry epoch seconds
    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();

    private static final Gson GSON = new Gson();

    public TokenManager(Path configDir, Logger logger) {
        this.logger = logger;
        this.storageDir = configDir.resolve("storage");
        this.tokensFile = storageDir.resolve("tokens.json");
        this.sessionsFile = storageDir.resolve("sessions.json");

        try {
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                if (logger != null) logger.log("INFO", "Created storage directory: " + storageDir);
            } else if (logger != null) logger.log("INFO", "Using storage directory: " + storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory", e);
        }

        loadTokens();
        loadSessions();
    }

    private void loadTokens() {
        try {
            if (!Files.exists(tokensFile)) {
                Files.write(tokensFile, GSON.toJson(Collections.singletonMap("tokens", Collections.emptyList())).getBytes(StandardCharsets.UTF_8));
                if (logger != null) logger.log("INFO", "Created tokens file: " + tokensFile);
                return;
            }
            String txt = Files.readString(tokensFile, StandardCharsets.UTF_8);
            Type t = new TypeToken<Map<String, List<Token>>>(){}.getType();
            Map<String, List<Token>> root = GSON.fromJson(txt, t);
            if (root == null) return;
            List<Token> list = root.getOrDefault("tokens", Collections.emptyList());
            for (Token tk : list) {
                tokens.put(tk.id, tk);
            }
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to load tokens: " + e.getMessage());
        }
    }

    private void loadSessions() {
        // sessions.json currently stores nonce cache expiries to survive restarts.
        try {
            if (!Files.exists(sessionsFile)) {
                // create an empty sessions file to make the layout consistent
                Files.writeString(sessionsFile, "{}", StandardCharsets.UTF_8);
                if (logger != null) logger.log("INFO", "Created sessions file: " + sessionsFile);
            } else if (logger != null) logger.log("INFO", "Using sessions file: " + sessionsFile);
            String txt = Files.readString(sessionsFile, StandardCharsets.UTF_8);
            Type t = new TypeToken<Map<String, Long>>(){}.getType();
            Map<String, Long> sess = GSON.fromJson(txt, t);
            if (sess != null) nonceCache.putAll(sess);
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to load sessions: " + e.getMessage());
        }
    }

    private void persistSessions() {
        try {
            String txt = GSON.toJson(nonceCache);
            Files.writeString(sessionsFile, txt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log("WARN", "Failed to persist sessions: " + e.getMessage());
        }
    }

    public boolean verifyHmac(String tokenId, String timestampStr, String nonce, String signature, String method, String path, String body, long allowedSkewSeconds) {
        if (tokenId == null || signature == null || timestampStr == null || nonce == null) return false;
        Token tk = tokens.get(tokenId);
        if (tk == null) return false;
        if (tk.revoked) return false;
        long ts;
        try { ts = Long.parseLong(timestampStr); } catch (NumberFormatException e) { return false; }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > allowedSkewSeconds) return false;

        // check nonce uniqueness
        Long existing = nonceCache.putIfAbsent(nonce, ts + allowedSkewSeconds);
        if (existing != null) return false;

        // cleanup old nonces occasionally
        if (nonceCache.size() > 1000) {
            long cutoff = now - (allowedSkewSeconds * 2);
            Iterator<Map.Entry<String, Long>> it = nonceCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue() < now) it.remove();
            }
            persistSessions();
        }

        // Build message the same way client does: METHOD\n{path}\n{timestamp}\n{nonce}\n{body}
        String bodyStr = body == null ? "" : body;
        String msg = method.toUpperCase() + "\n" + path + "\n" + timestampStr + "\n" + nonce + "\n" + bodyStr;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(tk.secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] out = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            String expected = bytesToHex(out);
            return expected.equalsIgnoreCase(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.log("ERROR", "HMAC verification failed: " + e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public boolean hasToken(String id) { return tokens.containsKey(id); }

    // lightweight token representation
    public static final class Token {
        public String id;
        public String secret;
        // policyId links this runtime token to a named policy in tokens.yaml
        public String policyId = null;
        public boolean revoked = false;
        public long expiry = 0; // epoch seconds, 0 = never
        public List<String> whitelist = Collections.emptyList();
        public List<String> blacklist = Collections.emptyList();
    }

    public Token createToken(String id, long expirySeconds, List<String> whitelist, List<String> blacklist) {
        String effectiveId = id != null && !id.isBlank() ? id : java.util.UUID.randomUUID().toString().replaceAll("-", "");
        byte[] rnd = new byte[32];
        new java.security.SecureRandom().nextBytes(rnd);
        String secret = bytesToHex(rnd);

        Token t = new Token();
        t.id = effectiveId;
        t.secret = secret;
        t.revoked = false;
        if (expirySeconds > 0) {
            t.expiry = Instant.now().getEpochSecond() + expirySeconds;
        } else if (expirySeconds == 0) {
            t.expiry = 0L;
        }
        if (whitelist != null) t.whitelist = whitelist;
        if (blacklist != null) t.blacklist = blacklist;

        tokens.put(effectiveId, t);
        persistTokens();
        return t;
    }

    public Token createToken(long ttlSeconds, List<String> whitelist, List<String> blacklist) {
        return createToken(null, ttlSeconds, whitelist, blacklist);
    }

    /**
     * Attach an external policy id (from tokens.yaml) to a runtime token and persist.
     */
    public void setTokenPolicyId(String tokenId, String policyId) {
        Token t = tokens.get(tokenId);
        if (t == null) return;
        t.policyId = policyId;
        persistTokens();
    }

    public boolean revokeToken(String id) {
        Token t = tokens.get(id);
        if (t == null) return false;
        t.revoked = true;
        persistTokens();
        return true;
    }

    public Token rotateToken(String id) {
        Token t = tokens.get(id);
        if (t == null) return null;
        if (t.revoked) return null;
        byte[] rnd = new byte[32];
        new java.security.SecureRandom().nextBytes(rnd);
        String secret = bytesToHex(rnd);
        t.secret = secret;
        // update expiry remains the same
        persistTokens();
        return t;
    }

    public Map<String, Token> listTokens() {
        return Collections.unmodifiableMap(tokens);
    }

    private void persistTokens() {
        try {
            Map<String, Object> root = new HashMap<>();
            root.put("tokens", tokens.values());
            String txt = GSON.toJson(root);
            Files.writeString(tokensFile, txt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log("WARN", "Failed to persist tokens: " + e.getMessage());
        }
    }
}
