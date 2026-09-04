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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
 */
public final class TokenManager {

    private final Path storageDir;
    private final Path tokensFile;
    private final Path sessionsFile;
    private final Path pickupsFile;
    private final Logger logger;

    // server-wide master key used to derive per-token HMAC keys via HKDF
    private final byte[] masterKey;

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    // nonce -> expiry epoch seconds
    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();

    private static final Gson GSON = new Gson();

    public TokenManager(Path configDir, Logger logger) {
        this.logger = logger;
        this.storageDir = configDir.resolve("storage");
        this.tokensFile = storageDir.resolve("tokens.json");
        this.sessionsFile = storageDir.resolve("sessions.json");
        this.pickupsFile = storageDir.resolve("pickups.json");
    private final ScheduledExecutorService sweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hb-pickup-sweeper");
        t.setDaemon(true);
        return t;
    });

        try {
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                if (logger != null) logger.log("INFO", "Created storage directory: " + storageDir);
            } else if (logger != null) logger.log("INFO", "Using storage directory: " + storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory", e);
        }

        this.masterKey = loadOrCreateMasterKey();
        loadTokens();
        loadSessions();
        loadPickups();
    }

    private byte[] loadOrCreateMasterKey() {
        Path mk = storageDir.resolve("master.key");
        try {
            if (Files.exists(mk)) {
                byte[] b = Files.readAllBytes(mk);
        // start periodic sweep to remove expired pickups every 5 minutes
        try {
            sweepExecutor.scheduleAtFixedRate(() -> {
                try { sweepExpiredPickups(); } catch (Exception e) { if (logger != null) logger.log("WARN", "Pickup sweep failed: " + e.getMessage()); }
            }, 300, 300, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
                return b;
            }
            byte[] b = new byte[32];
            new java.security.SecureRandom().nextBytes(b);
            Files.write(mk, b);
            try {
                java.nio.file.attribute.PosixFilePermission p1 = java.nio.file.attribute.PosixFilePermission.OWNER_READ;
                java.nio.file.attribute.PosixFilePermission p2 = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.Set.of(p1, p2);
                Files.setPosixFilePermissions(mk, perms);
            } catch (UnsupportedOperationException ignored) {}
            if (logger != null) logger.log("INFO", "Generated master key: " + mk);
            return b;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load/create master key", e);
        }
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

    public void shutdown() {
        try { sweepExecutor.shutdownNow(); } catch (Exception ignored) {}
    }

    private synchronized void sweepExpiredPickups() {
        long now = Instant.now().getEpochSecond();
        boolean removedAny = false;
        Iterator<Map.Entry<String, PickupRecord>> it = pickups.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PickupRecord> e = it.next();
            PickupRecord pr = e.getValue();
            if (pr.expiresAt < now) {
                it.remove();
                removedAny = true;
            }
        }
        if (removedAny) {
            persistPickups();
            if (logger != null) logger.log("INFO", "Swept expired pickup records");
        }
    }
            Map<String, Long> sess = GSON.fromJson(txt, t);
            if (sess != null) nonceCache.putAll(sess);
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to load sessions: " + e.getMessage());
        }
    }

    // pickups: temporary records storing plaintext secrets until consumed or expired
    public static final class PickupRecord {
        String pickupId;
        String tokenId;
        String secret; // plaintext, short-lived
        long expiresAt;
    }

    private final Map<String, PickupRecord> pickups = new ConcurrentHashMap<>();

    private void loadPickups() {
        try {
            if (!Files.exists(pickupsFile)) {
                Files.writeString(pickupsFile, "{}", StandardCharsets.UTF_8);
                if (logger != null) logger.log("INFO", "Created pickups file: " + pickupsFile);
                return;
            }
            String txt = Files.readString(pickupsFile, StandardCharsets.UTF_8);
            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<Map<String, PickupRecord>>(){}.getType();
            Map<String, PickupRecord> m = GSON.fromJson(txt, t);
            if (m != null) {
                long now = Instant.now().getEpochSecond();
                for (var e : m.entrySet()) {
                    PickupRecord pr = e.getValue();
                    if (pr.expiresAt >= now) pickups.put(e.getKey(), pr);
                }
                // persist cleaned pickups (remove expired ones)
                persistPickups();
            }
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to load pickups: " + e.getMessage());
        }
    }

    private synchronized void persistPickups() {
        try {
            String txt = GSON.toJson(pickups);
            Files.writeString(pickupsFile, txt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (logger != null) logger.log("WARN", "Failed to persist pickups: " + e.getMessage());
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
            byte[] key = deriveTokenKey(tk.id, tk.salt);
            SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");
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
        // do not store plaintext secret. Instead store a salt for HKDF derivation.
        public String salt;
        // human-friendly unique name (optional)
        public String name = null;
        // policyId links this runtime token to a named policy in tokens.yaml
        public String policyId = null;
        public boolean revoked = false;
        public long expiry = 0; // epoch seconds, 0 = never
        public List<String> whitelist = Collections.emptyList();
        public List<String> blacklist = Collections.emptyList();
    }

    public Token createToken(String id, long expirySeconds, List<String> whitelist, List<String> blacklist) {
        String effectiveId = id != null && !id.isBlank() ? id : java.util.UUID.randomUUID().toString().replaceAll("-", "");
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        String saltHex = bytesToHex(salt);

        Token t = new Token();
        t.id = effectiveId;
        t.salt = saltHex;
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

    public void setTokenName(String tokenId, String name) {
        Token t = tokens.get(tokenId);
        if (t == null) return;
        t.name = name;
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
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        t.salt = bytesToHex(salt);
        // update expiry remains the same
        persistTokens();
        return t;
    }

    public synchronized IssueResult rotateTokenWithPickup(String id, int pickupTtlSeconds) {
        Token t = rotateToken(id);
        if (t == null) return null;
        byte[] key = deriveTokenKey(t.id, t.salt);
        String secret = bytesToHex(key);
        String pickupId = java.util.UUID.randomUUID().toString();
        PickupRecord pr = new PickupRecord();
        pr.pickupId = pickupId;
        pr.tokenId = t.id;
        pr.secret = secret;
        pr.expiresAt = Instant.now().getEpochSecond() + Math.max(60, pickupTtlSeconds);
        pickups.put(pickupId, pr);
        persistPickups();
        IssueResult r = new IssueResult();
        r.pickupId = pickupId;
        r.tokenId = t.id;
        return r;
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

    // Derive per-token HMAC key using HKDF(SHA256) with masterKey, salt and tokenId as info
    private byte[] deriveTokenKey(String tokenId, String saltHex) {
        byte[] salt = hexToBytes(saltHex);
        return hkdfExpand(hkdfExtract(masterKey, salt), (tokenId).getBytes(StandardCharsets.UTF_8), 32);
    }

    private static byte[] hkdfExtract(byte[] ikm, byte[] salt) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(salt == null ? new byte[32] : salt, "HmacSHA256"));
            return mac.doFinal(ikm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int len) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            byte[] result = new byte[len];
            byte[] t = new byte[0];
            int loc = 0;
            int i = 1;
            while (loc < len) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                int copy = Math.min(t.length, len - loc);
                System.arraycopy(t, 0, result, loc, copy);
                loc += copy;
                i++;
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len/2];
        for (int i = 0; i < len; i += 2) data[i/2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        return data;
    }

    // Issue a token and create a temporary pickup record that contains the plaintext secret.
    // Returns pickupId (UUID string) or null on failure.
    public static final class IssueResult {
        public String pickupId;
        public String tokenId;
    }

    public synchronized IssueResult issueTokenWithPickup(String id, long expirySeconds, List<String> whitelist, List<String> blacklist, int pickupTtlSeconds) {
        Token t = createToken(id, expirySeconds, whitelist, blacklist);
        if (t == null) return null;
        // derive token secret (plaintext) from master key and salt/token id
        byte[] key = deriveTokenKey(t.id, t.salt);
        String secret = bytesToHex(key);

        // generate pickup id
        String pickupId = java.util.UUID.randomUUID().toString();
        PickupRecord pr = new PickupRecord();
        pr.pickupId = pickupId;
        pr.tokenId = t.id;
        pr.secret = secret;
        pr.expiresAt = Instant.now().getEpochSecond() + Math.max(60, pickupTtlSeconds);
        pickups.put(pickupId, pr);
        persistPickups();
        IssueResult r = new IssueResult();
        r.pickupId = pickupId;
        r.tokenId = t.id;
        return r;
    }

    // Retrieve and consume a pickup record atomically. Returns null if not found or expired.
    public synchronized PickupRecord consumePickup(String pickupId) {
        PickupRecord pr = pickups.get(pickupId);
        if (pr == null) return null;
        long now = Instant.now().getEpochSecond();
        if (pr.expiresAt < now) {
            pickups.remove(pickupId);
            persistPickups();
            return null;
        }
        pickups.remove(pickupId);
        persistPickups();
        return pr;
    }
}
