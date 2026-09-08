package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class NewAuthModelTest {

    @Test
    public void hkdfDerivationAndPermissionMatchingUseNewModel() {
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        byte[] secret = TokenManager.deriveSecret(master, salt, "root");
        assertEquals(32, secret.length);
        assertTrue(TokenManager.permissionMatches("server.log", List.of("server.*")));
        assertTrue(TokenManager.permissionMatches("world.tps", List.of("world.*")));
        assertTrue(TokenManager.permissionMatches("ping", List.of("*")));
        assertFalse(TokenManager.permissionMatches("admin.config.set", List.of("server.*")));
        assertTrue(TokenManager.permissionMatches("server.log", List.of("server.log")));
    }

    @Test
    public void canonicalRequestUsesNormalizedMethodPathAndJsonBody() {
        String canonical = TokenManager.canonicalRequest(
                "post",
                "/server/log?x=1",
                "1700000000",
                "abc123",
                "{\"message\":\"hello\",\"level\":\"info\"}"
        );

        assertEquals("POST\n/server/log\n1700000000\nabc123\n{\"level\":\"info\",\"message\":\"hello\"}", canonical);
    }

    @Test
    public void hmacVerifiesWithDerivedTokenKey() throws Exception {
        byte[] master = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) i;
        byte[] secret = TokenManager.deriveSecret(master, salt, "root");

        String body = "{\"message\":\"hello\",\"level\":\"info\"}";
        String canonical = TokenManager.canonicalRequest("POST", "/server/log", "1700000000", "abc123", body);

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        String signature = TokenManager.bytesToHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        assertEquals(signature, TokenManager.hmacHex(secret, canonical));
    }
}
