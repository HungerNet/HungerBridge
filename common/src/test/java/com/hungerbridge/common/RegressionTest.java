package com.hungerbridge.common;

import com.hungerbridge.common.log.LogLineFormatter;
import com.hungerbridge.common.security.Permissions;
import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Small regression tests for bugs fixed during the 2026-11 release-readiness pass. */
public final class RegressionTest {

    @Test
    public void streamLinesUseTheDocumentedConsoleFormat() {
        String line = LogLineFormatter.format(0L, "WARN", "hello", null);
        assertTrue(line.matches("\\[\\d{2}:\\d{2}:\\d{2} WARN]: hello"), line);
        String withTrace = LogLineFormatter.format(0L, "ERROR", "boom", new IllegalStateException("x"));
        assertTrue(withTrace.contains("\njava.lang.IllegalStateException: x"), withTrace);
        assertTrue(withTrace.contains("\n\tat "), "stack trace indentation must be preserved");
    }

    @Test
    public void legacyPermissionAliasesExpandToRealNodes() {
        List<String> expanded = Permissions.expand(List.of("server.info", "player.list", "world.read", "ping"));
        assertEquals(List.of("server.meta", "server.ping", "players.list", "world.*"), expanded);
        assertTrue(TokenManager.permissionMatches("world.chunks", expanded));
        assertFalse(TokenManager.permissionMatches("server.run", expanded));
    }

    @Test
    public void meaningfulPermissionDetection() {
        assertTrue(Permissions.isMeaningful("*"));
        assertTrue(Permissions.isMeaningful("system.*"));
        assertTrue(Permissions.isMeaningful("server.run"));
        assertFalse(Permissions.isMeaningful("admin.*"));
        assertFalse(Permissions.isMeaningful("server.info"));
    }

    @Test
    public void builtInDefaultPolicyOnlyGrantsRealNodes() {
        for (TokensConfig.TokenPolicy p : TokensConfig.defaults().policies.values()) {
            for (String node : p.permissions) {
                assertTrue(Permissions.isMeaningful(node), p.id + " grants dead node " + node);
            }
        }
    }

    @Test
    public void shippedPolicyTemplateOnlyGrantsRealNodes() throws Exception {
        Path dir = Files.createTempDirectory("hb-template");
        List<String> warnings = new ArrayList<>();
        Config cfg = Config.load(dir, (level, thread, msg) -> { if ("WARN".equals(level)) warnings.add(msg); });
        TokensConfig.TokenPolicy def = cfg.getTokensConfig().getPolicy("default");
        assertNotNull(def, "autogen policies.yaml must define the 'default' policy");
        for (String node : def.permissions) assertTrue(Permissions.isMeaningful(node), node);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("policies.yaml")), warnings.toString());
    }

    @Test
    public void verificationOrderDoesNotLeakTokenExistenceViaTimestamp() throws Exception {
        TokenManager tm = new TokenManager(Files.createTempDirectory("hb-order"), null);
        tm.createToken("exists", List.of("*"));
        long stale = System.currentTimeMillis() / 1000L - 10_000L;
        assertEquals(TokenManager.VerifyResult.BAD_TIMESTAMP,
                tm.verifyHmacDetailed("exists", Long.toString(stale), "n1", "00", "GET", "/ping", "", 300));
        assertEquals(TokenManager.VerifyResult.BAD_TIMESTAMP,
                tm.verifyHmacDetailed("missing", Long.toString(stale), "n1", "00", "GET", "/ping", "", 300));
    }

    @Test
    public void forgedRequestsDoNotPopulateTheReplayCache() throws Exception {
        TokenManager tm = new TokenManager(Files.createTempDirectory("hb-replay"), null);
        tm.createToken("t", List.of("*"));
        String ts = Long.toString(System.currentTimeMillis() / 1000L);
        for (int i = 0; i < 5; i++) {
            assertEquals(TokenManager.VerifyResult.BAD_SIGNATURE, tm.verifyHmacDetailed("t", ts, "nonce-x", "00", "GET", "/ping", "", 300));
        }
        java.lang.reflect.Field f = TokenManager.class.getDeclaredField("nonceExpiries");
        f.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) f.get(tm)).isEmpty());
    }

    @Test
    public void commandErrorsAreShownToTheSender() {
        List<String> out = CommonCommandHandler.handle(null, new String[] {"reload"});
        assertEquals(List.of("Error: Server unavailable."), out);
        assertEquals(List.of("Error: Unknown subcommand."), CommonCommandHandler.handle(null, new String[] {"bogus"}));
    }

    @Test
    public void rotateIssuesAPickupForTheNewSecret() throws Exception {
        // Regression: "/hb token rotate" changed the secret without any way to retrieve it.
        Path dir = Files.createTempDirectory("hb-rotate");
        Config cfg = Config.load(dir, (l, t, m) -> {});
        TokenManager tm = new TokenManager(dir, null);
        cfg.setTokenManager(tm);
        BridgeServer server = new BridgeServer(dir, cfg, (l, t, m) -> {}, new com.hungerbridge.common.testing.FakeExecutor());
        TokenManager.IssueResult created = tm.issueTokenWithPickup("rot", 0L, null, 300);
        String oldSecret = tm.consumePickup(created.pickupId, created.passkey).secret;

        List<String> out = CommonCommandHandler.handle(server, new String[] {"token", "rotate", "rot"});
        String url = out.stream().filter(l -> l.contains("/pickup/")).findFirst().orElseThrow();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/pickup/([0-9a-f-]+)\\?passkey=([0-9a-f]+)").matcher(url);
        assertTrue(m.find(), url);
        String newSecret = tm.consumePickup(m.group(1), m.group(2)).secret;
        assertNotEquals(oldSecret, newSecret);
        assertEquals(List.of("Error: Token not found or revoked: nope"), CommonCommandHandler.handle(server, new String[] {"token", "rotate", "nope"}));
    }

    @Test
    public void ssePayloadEscapingAndRedaction() {
        assertEquals("data:a\\nb\\rc\n\n", LogDistributor.frame("a\nb\rc"));
        assertEquals("Pickup passkey: [REDACTED] url /pickup/x?passkey=[REDACTED]",
                LogDistributor.sanitizeForBroadcast("Pickup passkey: abc123 url /pickup/x?passkey=DEADBEEF"));
    }
}
