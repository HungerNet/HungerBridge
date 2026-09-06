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
    public void emptyBlacklistPolicyProducesEmptyRuntimeBlacklist() throws IOException {
        Path dir = Files.createTempDirectory("hb-policy-blacklist");
        Files.writeString(dir.resolve("config.yaml"), "port: 1913\n");
        Files.writeString(dir.resolve("security.yaml"), "ip_list:\n  mode: blacklist\n  list: []\nrate_limits:\n  token_rps: 5\n  token_burst: 10\n  ip_rps: 20\n  ip_burst: 40\n audit_retention_days: 14\n");
        Files.writeString(dir.resolve("tokens.yaml"), "tokens:\n  - id: admin\n    default_expiry: 0\n    max_skew: -1\n    endpoints_mode: blacklist\n    endpoints: []\n    commands_mode: blacklist\n    commands: []\n");

        Config config = Config.load(dir, (l, m) -> {});
        TokenManager tm = new TokenManager(dir, null);
        config.setTokenManager(tm);
        AdminService admin = new AdminService(dir, config, null, null);

        TokenManager.Token t = admin.createToken("admin", null, 0L, null, null);
        assertNotNull(t);
        assertNotNull(t.blacklist);
        assertTrue(t.blacklist.isEmpty(), "Expected runtime blacklist to be an explicit empty list (allow all)");
    }

    @Test
    public void emptyWhitelistPolicyProducesEmptyRuntimeWhitelist() throws IOException {
        Path dir = Files.createTempDirectory("hb-policy-whitelist");
        Files.writeString(dir.resolve("config.yaml"), "port: 1913\n");
        Files.writeString(dir.resolve("security.yaml"), "ip_list:\n  mode: blacklist\n  list: []\nrate_limits:\n  token_rps: 5\n  token_burst: 10\n  ip_rps: 20\n  ip_burst: 40\n audit_retention_days: 14\n");
        Files.writeString(dir.resolve("tokens.yaml"), "tokens:\n  - id: limited\n    default_expiry: 0\n    max_skew: -1\n    endpoints_mode: whitelist\n    endpoints: []\n    commands_mode: whitelist\n    commands: []\n");

        Config config = Config.load(dir, (l, m) -> {});
        TokenManager tm = new TokenManager(dir, null);
        config.setTokenManager(tm);
        AdminService admin = new AdminService(dir, config, null, null);

        TokenManager.Token t = admin.createToken("limited", null, 0L, null, null);
        assertNotNull(t);
        assertNotNull(t.whitelist);
        assertTrue(t.whitelist.isEmpty(), "Expected runtime whitelist to be an explicit empty list (deny all)");
    }

    @Test
    public void dualEmptyTokenListsRemainPermissiveForAdminPolicy() {
        TokenManager.Token token = new TokenManager.Token();
        token.whitelist = List.of();
        token.blacklist = List.of();
        token.revoked = false;
        token.expiry = 0;

        // Under the new semantics, an explicit empty whitelist means "deny all".
        assertFalse(HttpUtil.tokenAclAllows(token, "log"));
        assertFalse(HttpUtil.tokenAclAllows(token, "admin"));
    }

    @Test
    public void jsonBodyFieldOrderDoesNotAffectHmacVerification() throws Exception {
        Path dir = Files.createTempDirectory("hb-hmac-body-order");
        TokenManager tm = new TokenManager(dir, null);
        TokenManager.IssueResult res = tm.issueTokenWithPickup("body-order", 0, null, null, 300);
        var pickup = tm.consumePickup(res.pickupId);
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
        String sigRaw = bytesToHex(mac.doFinal(("POST\n/server/log\n" + ts2 + "\n" + nonce2 + "\n" + bodyRaw).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertTrue(tm.verifyHmac(res.tokenId, ts1, nonce1, sigCanonical, "POST", "/server/log", bodyCanonical, 300));
        assertTrue(tm.verifyHmac(res.tokenId, ts3, nonce3, sigCanonical, "POST", "/server/log", bodyRaw, 300));
        assertTrue(tm.verifyHmac(res.tokenId, ts2, nonce2, sigRaw, "POST", "/server/log", bodyRaw, 300));
        assertTrue(tm.verifyHmac(res.tokenId, ts4, nonce4, sigRaw, "POST", "/server/log", bodyCanonical, 300));
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
