package com.hungerbridge.common.security;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    public static byte[] deriveSecret(byte[] masterKey, byte[] salt, String id) {
        byte[] effectiveMaster = masterKey == null ? new byte[32] : masterKey;
        byte[] effectiveSalt = salt == null ? new byte[16] : salt;
        byte[] prk = hkdfExtract(effectiveMaster, effectiveSalt);
        byte[] info = id == null ? new byte[0] : id.getBytes(StandardCharsets.UTF_8);
        return hkdfExpand(prk, info, 32);
    }

    public static String canonicalRequest(String method, String path, String timestamp, String nonce, String body) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase();
        String normalizedPath = normalizePath(path);
        String canonicalBody = canonicalizeBody(body);
        return normalizedMethod + "\n" + normalizedPath + "\n" + timestamp + "\n" + nonce + "\n" + canonicalBody;
    }

    public static boolean permissionMatches(String node, List<String> permissions) {
        if (node == null || node.isBlank()) return false;
        if (permissions == null || permissions.isEmpty()) return false;
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) continue;
            if ("*".equals(permission)) return true;
            if (permission.endsWith(".*") && node.startsWith(permission.substring(0, permission.length() - 1))) return true;
            if (permission.equals(node)) return true;
        }
        return false;
    }

    public static String hmacHex(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return bytesToHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build HMAC", e);
        }
    }

    public static String normalizePath(String path) {
        String normalized = path == null ? "/" : path.trim();
        if (normalized.isEmpty()) return "/";
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            try {
                java.net.URI uri = java.net.URI.create(normalized);
                normalized = uri.getPath();
            } catch (Exception ignored) {
                normalized = normalized.replaceFirst("^[^/]+://[^/]+", "");
            }
        }
        normalized = normalized.split("\\?", 2)[0].split("#", 2)[0];
        if (normalized.isEmpty()) return "/";
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "/" : normalized;
    }

    public static String canonicalizeBody(String body) {
        if (body == null || body.isBlank()) return "";
        String trimmed = body.trim();
        try {
            JsonElement element = JsonParser.parseString(trimmed);
            return canonicalizeJson(element);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    public static String canonicalizeJson(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonPrimitive()) return element.toString();
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) sb.append(',');
                sb.append(canonicalizeJson(child));
                first = false;
            }
            sb.append(']');
            return sb.toString();
        }
        Map<String, JsonElement> sorted = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
            if (!first) sb.append(',');
            sb.append(GSON.toJson(entry.getKey()));
            sb.append(':');
            sb.append(canonicalizeJson(entry.getValue()));
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    public byte[] deriveTokenSecret(Token token) {
        if (token == null || token.salt == null || token.salt.isBlank()) {
            throw new IllegalArgumentException("token does not have a usable salt");
        }
        return deriveSecret(masterKey, hexToBytes(token.salt), token.id);
    }

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

    private final ScheduledExecutorService sweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HungerBridge");
        t.setDaemon(true);
        return t;
    });

    public TokenManager(Path configDir, Logger logger) {
        this.logger = logger;
        this.storageDir = configDir.resolve("storage");
        this.tokensFile = storageDir.resolve("tokens.json");
        this.sessionsFile = storageDir.resolve("sessions.json");
        this.pickupsFile = storageDir.resolve("pickups.json");

        try {
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                if (logger != null) logger.log("INFO", "Created storage directory: " + storageDir);
            } else if (logger != null) logger.log("INFO", "Using storage directory: " + storageDir);
            ensureDirectoryPermissions(storageDir, logger);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory", e);
        }

        this.masterKey = loadOrCreateMasterKey();
        loadTokens();
        // If no tokens exist, leave the token store empty. Tokens should be
        // provisioned explicitly via operator tooling; do not auto-bootstrap
        // a privileged token.
        loadSessions();
        loadPickups();

        // start periodic sweep to remove expired pickups every 5 minutes
        try {
            sweepExecutor.scheduleAtFixedRate(() -> {
                try { sweepExpiredPickups(); } catch (Exception e) { if (logger != null) logger.log("WARN", "Pickup sweep failed: " + e.getMessage()); }
            }, 300, 300, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private byte[] loadOrCreateMasterKey() {
        Path mk = storageDir.resolve("master.key");
        try {
            if (Files.exists(mk)) {
                ensureFilePermissions(mk, logger);
                byte[] b = Files.readAllBytes(mk);
                return b;
            }
            byte[] b = new byte[32];
            new java.security.SecureRandom().nextBytes(b);
            Files.write(mk, b);
            ensureFilePermissions(mk, logger);
            if (logger != null) logger.log("INFO", "Generated master key: " + mk);
            return b;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load/create master key", e);
        }
    }

    private void setOwnerOnlyPerms(Path path) {
        try {
            java.nio.file.attribute.PosixFilePermission ownerRead = java.nio.file.attribute.PosixFilePermission.OWNER_READ;
            java.nio.file.attribute.PosixFilePermission ownerWrite = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(ownerRead, ownerWrite);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // Filesystem does not support POSIX permissions; rely on the containing directory and OS-level controls.
        } catch (IOException e) {
            if (logger != null) logger.log("WARN", "Failed to tighten permissions on " + path + ": " + e.getMessage());
        }
    }

    private static void ensureDirectoryPermissions(Path dir, Logger logger) {
        if (!Files.isDirectory(dir)) {
            if (logger != null) logger.log("WARN", "Path is not a directory: " + dir);
            return;
        }
        try {
            if (!Files.isReadable(dir) || !Files.isWritable(dir) || !Files.isExecutable(dir)) {
                if (logger != null) logger.log("WARN", "Storage directory has incorrect permissions; correcting: " + dir);
                java.nio.file.attribute.PosixFilePermission ownerRead = java.nio.file.attribute.PosixFilePermission.OWNER_READ;
                java.nio.file.attribute.PosixFilePermission ownerWrite = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
                java.nio.file.attribute.PosixFilePermission ownerExecute = java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(ownerRead, ownerWrite, ownerExecute);
                Files.setPosixFilePermissions(dir, perms);
                if (logger != null) logger.log("INFO", "Corrected storage directory permissions to rwx------");
            }
        } catch (UnsupportedOperationException ignored) {
            // Filesystem does not support POSIX permissions.
        } catch (IOException e) {
            if (logger != null) logger.log("ERROR", "Failed to correct storage directory permissions on " + dir + ": " + e.getMessage());
        }
    }

    private static void ensureFilePermissions(Path file, Logger logger) {
        if (!Files.isRegularFile(file)) {
            if (logger != null) logger.log("WARN", "Path is not a regular file: " + file);
            return;
        }
        try {
            java.nio.file.attribute.PosixFilePermission ownerRead = java.nio.file.attribute.PosixFilePermission.OWNER_READ;
            java.nio.file.attribute.PosixFilePermission ownerWrite = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(ownerRead, ownerWrite);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            // Filesystem does not support POSIX permissions.
        } catch (IOException e) {
            if (logger != null) logger.log("WARN", "Failed to set file permissions on " + file + ": " + e.getMessage());
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
            Map<String, Long> sess = GSON.fromJson(txt, t);
            if (sess != null) nonceCache.putAll(sess);
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to load sessions: " + e.getMessage());
        }
    }

    // pickups: temporary records storing plaintext secrets until consumed or expired
    public static final class PickupRecord {
        public String pickupId;
        public String tokenId;
        public String secret; // plaintext, short-lived
        public long expiresAt;
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

    private synchronized void persistPickups() {
        try {
            String txt = GSON.toJson(pickups);
            Files.writeString(pickupsFile, txt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (logger != null) logger.log("WARN", "Failed to persist pickups: " + e.getMessage());
        }
    }

    private synchronized void persistSessions() {
        try {
            String txt = GSON.toJson(nonceCache);
            Files.writeString(sessionsFile, txt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (logger != null) logger.log("WARN", "Failed to persist sessions: " + e.getMessage());
        }
    }

    public boolean verifyHmac(String tokenId, String timestampStr, String nonce, String signature, String method, String path, String body, long allowedSkewSeconds) {
        // Detailed validation with early debug logs for root-cause diagnosis.
        if (tokenId == null) { if (logger != null) logger.log("DEBUG", "verifyHmac: missing tokenId"); return false; }
        if (signature == null) { if (logger != null) logger.log("DEBUG", "verifyHmac: missing signature"); return false; }
        if (timestampStr == null) { if (logger != null) logger.log("DEBUG", "verifyHmac: missing timestamp"); return false; }
        if (nonce == null) { if (logger != null) logger.log("DEBUG", "verifyHmac: missing nonce"); return false; }

        Token tk = tokens.get(tokenId);
        if (tk == null) { if (logger != null) logger.log("DEBUG", "verifyHmac: token not found: " + tokenId); return false; }
        if (tk.revoked) { if (logger != null) logger.log("DEBUG", "verifyHmac: token revoked: " + tokenId); return false; }

        long ts;
        try { ts = Long.parseLong(timestampStr); } catch (NumberFormatException e) { if (logger != null) logger.log("DEBUG", "verifyHmac: invalid timestamp: " + timestampStr); return false; }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > allowedSkewSeconds) { if (logger != null) logger.log("DEBUG", "verifyHmac: timestamp skew (now=" + now + ", ts=" + ts + ")"); return false; }

        Long existing = nonceCache.putIfAbsent(nonce, ts + allowedSkewSeconds);
        if (existing != null) { if (logger != null) logger.log("DEBUG", "verifyHmac: nonce replay: " + nonce); return false; }

        if (nonceCache.size() > 1000) {
            long cutoff = now - (allowedSkewSeconds * 2);
            Iterator<Map.Entry<String, Long>> it = nonceCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue() < now || e.getValue() < cutoff) it.remove();
            }
            persistSessions();
        } else {
            persistSessions();
        }

        String rawBody = body == null ? "" : body;
        String canonicalBody = canonicalizeBodyForHmac(rawBody);
        String normalizedPath = normalizePath(path);
        String methodName = method == null ? "" : method.trim().toUpperCase();
        String canonicalMsg = methodName + "\n" + normalizedPath + "\n" + timestampStr + "\n" + nonce + "\n" + canonicalBody;
        String rawMsg = methodName + "\n" + normalizedPath + "\n" + timestampStr + "\n" + nonce + "\n" + rawBody.trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = deriveTokenKey(tk.id, tk.salt);
            SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA256");
            mac.init(keySpec);
            String expectedCanonical = bytesToHex(mac.doFinal(canonicalMsg.getBytes(StandardCharsets.UTF_8)));
            if (expectedCanonical.equalsIgnoreCase(signature)) return true;
            if (!rawMsg.equals(canonicalMsg)) {
                mac.reset();
                mac.init(keySpec);
                String expectedRaw = bytesToHex(mac.doFinal(rawMsg.getBytes(StandardCharsets.UTF_8)));
                if (expectedRaw.equalsIgnoreCase(signature)) return true;
                if (logger != null) {
                    String derivedHex = bytesToHex(key);
                    logger.log("DEBUG", "HMAC mismatch for token='" + tk.id + "' providedSig='" + signature + "' derivedKey='" + derivedHex + "' expectedCanonical='" + expectedCanonical + "' expectedRaw='" + expectedRaw + "'");
                }
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            if (logger != null) logger.log("ERROR", "HMAC verification failed: " + e.getMessage());
            return false;
        }
        return false;
    }

    public enum VerifyResult {
        OK,
        NO_TOKEN,
        REVOKED,
        EXPIRED,
        BAD_TIMESTAMP,
        NONCE_REPLAY,
        BAD_SIGNATURE,
        INTERNAL_ERROR
    }

    /**
     * Detailed HMAC verification returning a VerifyResult for precise failure reasons.
     */
    public VerifyResult verifyHmacDetailed(String tokenId, String timestampStr, String nonce, String signature, String method, String path, String body, long allowedSkewSeconds) {
        if (tokenId == null) return VerifyResult.NO_TOKEN;
        Token tk = tokens.get(tokenId);
        if (tk == null) return VerifyResult.NO_TOKEN;
        if (tk.revoked) return VerifyResult.REVOKED;
        long ts;
        try { ts = Long.parseLong(timestampStr); } catch (NumberFormatException e) { return VerifyResult.BAD_TIMESTAMP; }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > allowedSkewSeconds) return VerifyResult.BAD_TIMESTAMP;

        Long existing = nonceCache.putIfAbsent(nonce, ts + allowedSkewSeconds);
        if (existing != null) return VerifyResult.NONCE_REPLAY;

        // persist/cleanup as before
        if (nonceCache.size() > 1000) {
            long cutoff = now - (allowedSkewSeconds * 2);
            Iterator<Map.Entry<String, Long>> it = nonceCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue() < now || e.getValue() < cutoff) it.remove();
            }
            persistSessions();
        } else {
            persistSessions();
        }

        try {
            String rawBody = body == null ? "" : body;
            String canonicalBody = canonicalizeBodyForHmac(rawBody);
            String normalizedPath = normalizePath(path);
            String msgCanonical = (method == null ? "" : method.trim().toUpperCase()) + "\n" + normalizedPath + "\n" + timestampStr + "\n" + nonce + "\n" + canonicalBody;
            String rawMsg = (method == null ? "" : method.trim().toUpperCase()) + "\n" + normalizedPath + "\n" + timestampStr + "\n" + nonce + "\n" + rawBody.trim();
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] key = deriveTokenKey(tk.id, tk.salt);
            javax.crypto.spec.SecretKeySpec ks = new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256");
            mac.init(ks);
            String exp = bytesToHex(mac.doFinal(msgCanonical.getBytes(StandardCharsets.UTF_8)));
            if (exp.equalsIgnoreCase(signature)) return VerifyResult.OK;
            if (!rawMsg.equals(msgCanonical)) {
                mac.reset();
                mac.init(ks);
                String expRaw = bytesToHex(mac.doFinal(rawMsg.getBytes(StandardCharsets.UTF_8)));
                if (expRaw.equalsIgnoreCase(signature)) return VerifyResult.OK;
            }
            return VerifyResult.BAD_SIGNATURE;
        } catch (Exception e) {
            if (logger != null) logger.log("ERROR", "HMAC verification error: " + e.getMessage());
            return VerifyResult.INTERNAL_ERROR;
        }
    }

    public static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String canonicalizeBodyForHmac(String body) {
        if (body == null || body.isBlank()) return "";
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return "";
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(trimmed);
            if (el == null || el.isJsonNull()) return "";
            return canonicalizeJson(el);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    public boolean hasToken(String id) { return tokens.containsKey(id); }

    public static final class Token {
        public String id;
        public String salt;
        public boolean revoked = false;
        public long expiry = 0;
        public java.util.List<String> permissions = new java.util.ArrayList<>();
        public int maxSkew = -1;
    }

    public Token createToken(String id, long expirySeconds, List<String> permissions) {
        String effectiveId = id != null && !id.isBlank() ? id : java.util.UUID.randomUUID().toString().replaceAll("-", "");
        // enforce uniqueness: do not allow creating a token with an id that
        // already exists or that is pending pickup
        if (effectiveId != null && tokens.containsKey(effectiveId)) {
            if (logger != null) logger.log("WARN", "Create token failed: id already exists: " + effectiveId);
            return null;
        }
        for (PickupRecord pr : pickups.values()) {
            if (pr != null && pr.tokenId != null && pr.tokenId.equals(effectiveId)) {
                if (logger != null) logger.log("WARN", "Create token failed: id already pending pickup: " + effectiveId);
                return null;
            }
        }
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
        if (permissions != null) {
            t.permissions = new java.util.ArrayList<>(permissions);
        }

        tokens.put(effectiveId, t);
        persistTokens();
        return t;
    }

    public Token createToken(long ttlSeconds, List<String> permissions) {
        return createToken(null, ttlSeconds, permissions);
    }

    /**
     * Attach an external policy id (from policies.yaml) to a runtime token and persist.
     */
    public void setTokenPolicyId(String tokenId, String policyId) {
        // policy id is referenced externally only; it is not persisted in tokens.json
        if (tokenId == null || tokenId.isBlank()) return;
        Token t = tokens.get(tokenId);
        if (t == null) return;
        persistTokens();
    }

    public void persistTokenPolicyState(String tokenId, List<String> permissions, int maxSkew) {
        Token t = tokens.get(tokenId);
        if (t == null) return;
        t.permissions = permissions == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(permissions);
        t.maxSkew = maxSkew;
        persistTokens();
    }

    public boolean revokeToken(String id) {
        Token t = tokens.get(id);
        if (t == null) return false;
        t.revoked = true;
        persistTokens();
        return true;
    }

    public boolean removeToken(String id) {
        Token t = tokens.remove(id);
        if (t == null) return false;
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
        return deriveTokenKey(tokenId, salt);
    }

    public byte[] deriveTokenKey(String tokenId, byte[] salt) {
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

    public synchronized IssueResult issueTokenWithPickup(String id, long expirySeconds, List<String> permissions, int pickupTtlSeconds) {
        Token t = createToken(id, expirySeconds, permissions);
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

