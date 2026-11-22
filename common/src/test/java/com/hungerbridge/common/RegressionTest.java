package com.hungerbridge.common;

import com.hungerbridge.common.message.BridgeMessage;
import com.hungerbridge.common.security.Permissions;
import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for fixed bugs (see PLAN.md §10). */
public final class RegressionTest {

    @Test
    public void legacyPermissionNodesNoLongerGrantAnything() {
        // Protocol v2 removed the aliases; the names are now plain unknown nodes.
        List<String> nodes = Permissions.normalize(List.of("server.info", " player.list ", "world.read", "ping", "ping"));
        assertEquals(List.of("server.info", "player.list", "world.read", "ping"), nodes);
        assertFalse(TokenManager.permissionMatches("server.meta", nodes));
        assertFalse(Permissions.isMeaningful("server.info"));
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
        Config cfg = Config.load(dir, entry -> { if ("WARN".equals(entry.level())) warnings.add(entry.message()); });
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
        assertEquals(List.of(BridgeMessage.header("Reload"), BridgeMessage.error("The bridge is not running")),
                CommonCommandHandler.handle(null, new String[] {"reload"}));
        List<BridgeMessage> unknown = CommonCommandHandler.handle(null, new String[] {"bogus"});
        assertEquals(BridgeMessage.error("Unknown command: bogus"), unknown.get(1));
    }

    @Test
    public void rotateIssuesAPickupForTheNewSecret() throws Exception {
        // Regression: "/hb token rotate" changed the secret without any way to retrieve it.
        Path dir = Files.createTempDirectory("hb-rotate");
        Config cfg = Config.load(dir, entry -> {});
        TokenManager tm = new TokenManager(dir, null);
        cfg.setTokenManager(tm);
        BridgeServer server = new BridgeServer(dir, cfg, entry -> {}, new com.hungerbridge.common.testing.FakeExecutor());
        TokenManager.IssueResult created = tm.issueTokenWithPickup("rot", 0L, null, 300);
        String oldSecret = tm.consumePickup(created.pickupId, created.passkey).secret;

        List<BridgeMessage> out = CommonCommandHandler.handle(server, new String[] {"token", "rotate", "rot"});
        String url = out.stream().map(BridgeMessage::plain).filter(l -> l.contains("/pickup/")).findFirst().orElseThrow();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/pickup/([0-9a-f-]+)\\?passkey=([0-9a-f]+)").matcher(url);
        assertTrue(m.find(), url);
        String newSecret = tm.consumePickup(m.group(1), m.group(2)).secret;
        assertNotEquals(oldSecret, newSecret);
        assertEquals(BridgeMessage.error("No active token with id nope"),
                CommonCommandHandler.handle(server, new String[] {"token", "rotate", "nope"}).get(1));
    }
}
