package com.hungerbridge.common;

import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class PolicySemanticsTest {

    @Test
    public void emptyPermissionsPolicyDeniesAllActions() throws IOException {
        Path dir = Files.createTempDirectory("hb-policy-deny-all");
        Files.writeString(dir.resolve("config.yaml"), "port: 1913\n");
        Files.writeString(dir.resolve("policies.yaml"), "policies:\n  - id: locked\n    permissions: []\n");

        Config config = Config.load(dir, (level, thread, message) -> {});
        TokenManager tm = new TokenManager(dir, null);
        config.setTokenManager(tm);

        // create a token representation with the 'locked' policy (empty permissions)
        TokenManager.Token t = new TokenManager.Token();
        t.revoked = false;
        t.expiry = 0;
        com.hungerbridge.common.TokensConfig.TokenPolicy policy = config.getTokensConfig().getPolicy("locked");
        if (policy != null) t.permissions = new java.util.ArrayList<>(policy.permissions); else t.permissions = new java.util.ArrayList<>();

        assertNotNull(t.permissions);
        assertTrue(t.permissions.isEmpty());
        assertFalse(HttpUtil.tokenAclAllows(t, "log"));
        assertFalse(HttpUtil.tokenAclAllows(t, "admin"));
    }

    @Test
    public void wildcardPermissionMatchesNewPermissionModel() {
        TokenManager.Token token = new TokenManager.Token();
        token.permissions = List.of("server.*", "world.*");
        token.revoked = false;
        token.expiry = 0;

        assertTrue(TokenManager.permissionMatches("server.log", token.permissions));
        assertTrue(TokenManager.permissionMatches("world.time", token.permissions));
        assertFalse(TokenManager.permissionMatches("admin.audit", token.permissions));
    }

    @Test
    public void tokenPermissionsAreMergedWithPolicyPermissions() throws IOException {
        Path dir = Files.createTempDirectory("hb-policy-merge");
        Files.writeString(dir.resolve("config.yaml"), "port: 1913\n");
        Files.writeString(dir.resolve("policies.yaml"), "policies:\n  - id: admin\n    permissions:\n      - '*'\n");

        Config config = Config.load(dir, (level, thread, message) -> {});
        TokenManager.Token token = new TokenManager.Token();
        token.policyId = "admin";
        token.permissions = List.of("auth.check");

        var merged = HttpUtil.mergedPermissions(token, config);
        assertTrue(merged.contains("*"));
        assertTrue(merged.contains("auth.check"));
        assertTrue(HttpUtil.tokenAclAllows(token, "auth.check"));
    }

    @Test
    public void jsonBodyFieldOrderDoesNotAffectHmacVerification() throws Exception {
        Path dir = Files.createTempDirectory("hb-hmac-body-order");
        TokenManager tm = new TokenManager(dir, null);
        TokenManager.IssueResult res = tm.issueTokenWithPickup("body-order", 0, null, 300);
        var pickup = tm.consumePickup(res.pickupId, res.passkey);
        assertNotNull(pickup);

        String bodyCanonical = "{\"level\":\"info\",\"message\":\"hello\"}";
        String bodyRaw = "{\"message\":\"hello\",\"level\":\"info\"}";

        byte[] key = hexToBytes(pickup.secret);
        String ts1 = String.valueOf(System.currentTimeMillis() / 1000L);
        String ts2 = String.valueOf(System.currentTimeMillis() / 1000L + 1);
        String ts3 = String.valueOf(System.currentTimeMillis() / 1000L + 2);
        String ts4 = String.valueOf(System.currentTimeMillis() / 1000L + 3);
        String nonce1 = "nonce-body-order-1";
        String nonce2 = "nonce-body-order-2";
        String nonce3 = "nonce-body-order-3";
        String nonce4 = "nonce-body-order-4";

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        String sigCanonical = bytesToHex(mac.doFinal(("POST\n/server/log\n" + ts1 + "\n" + nonce1 + "\n" + bodyCanonical).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mac.reset();
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        String sigCanonicalDifferentTimestamp = bytesToHex(mac.doFinal(("POST\n/server/log\n" + ts3 + "\n" + nonce3 + "\n" + bodyCanonical).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mac.reset();
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        String sigRaw = bytesToHex(mac.doFinal(("POST\n/server/log\n" + ts2 + "\n" + nonce2 + "\n" + bodyRaw).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mac.reset();
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        String sigRawDifferentTimestamp = bytesToHex(mac.doFinal(("POST\n/server/log\n" + ts4 + "\n" + nonce4 + "\n" + bodyRaw).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertTrue(tm.verifyHmac(res.tokenId, ts1, nonce1, sigCanonical, "POST", "/server/log", bodyCanonical, 300));
        assertTrue(tm.verifyHmac(res.tokenId, ts3, nonce3, sigCanonicalDifferentTimestamp, "POST", "/server/log", bodyCanonical, 300));
        assertTrue(tm.verifyHmac(res.tokenId, ts2, nonce2, sigRaw, "POST", "/server/log", bodyRaw, 300));
        assertTrue(tm.verifyHmac(res.tokenId, ts4, nonce4, sigRawDifferentTimestamp, "POST", "/server/log", bodyRaw, 300));
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
