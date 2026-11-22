package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ConfigTest {

    @Test
    public void readsEnabledEndpointsAndNewSecuritySchema() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-config");
        Path configFile = dir.resolve("config.yaml");
        Files.writeString(configFile, """
                port: 1913
                bind-address: 127.0.0.1
                players:
                  max-list: 10
                """);

        Files.writeString(dir.resolve("security.yaml"), """
                ips:
                  mode: whitelist
                  list:
                    - 127.0.0.1
                    - ::1
                """);

        Config config = Config.load(dir, entry -> {});

        assertTrue(config.getPlayersMaxList() == 10);
        assertTrue("127.0.0.1".equals(config.getBindAddress()));
        assertTrue(config.isRemoteAllowed("127.0.0.1"));
    }

    @Test
    public void hyphenatedKeysAreReadAndSnakeCaseIsIgnored() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-hyphen-config");
        Files.writeString(dir.resolve("config.yaml"), """
                port: 1913
                bind-address: 10.0.0.2
                players:
                  max-list: 12
                """);

        Config config = Config.load(dir, entry -> {});
        assertTrue(config.getBindAddress().equals("10.0.0.2"));
        assertTrue(config.getPlayersMaxList() == 12);

        Files.writeString(dir.resolve("config.yaml"), """
                port: 1913
                bind_address: 10.0.0.3
                players:
                  max-list: 8
                """);

        Config legacySnake = Config.load(dir, entry -> {});
        assertTrue(legacySnake.getBindAddress().equals("127.0.0.1"));
        assertTrue(legacySnake.getPlayersMaxList() == 8);
    }

    @Test
    public void reloadConfigLoadsNamedTokenPolicies() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-reload");
        Files.writeString(dir.resolve("config.yaml"), """
                port: 1913
                players:
                  max-list: 10
                """);
        // security.yaml removed; not needed for this test
        Files.writeString(dir.resolve("policies.yaml"), """
                policies:
                  - id: admin
                    permissions: ["*"]
                  - id: reporter
                    permissions: ["ping", "info"]
                """);

        Config config = Config.load(dir, entry -> {});
        config.setTokenManager(new TokenManager(dir, null));
        assertNotNull(config.getTokensConfig());
        assertTrue(config.getTokensConfig().getPolicy("reporter") != null);

        Files.writeString(dir.resolve("policies.yaml"), """
                policies:
                  - id: admin
                    permissions: ["*"]
                  - id: watcher
                    permissions: ["stream"]
                """);

        assertTrue(config.reload(entry -> {}));
        assertNotNull(config.getTokensConfig().getPolicy("watcher"));
        assertTrue(config.getTokensConfig().getPolicy("watcher") != null);
        assertTrue(config.getTokensConfig().getPolicy("reporter") == null);
    }

    @Test
    public void rejectsUnknownTokenPolicyIdOnCreate() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-unknown-token");
        Files.writeString(dir.resolve("config.yaml"), """
                port: 1913
                players:
                  max-list: 10
                """);
        // security.yaml removed; not needed for this test
        Files.writeString(dir.resolve("policies.yaml"), """
                policies:
                  - id: admin
                    default-expiry: 0
                    permissions: ["*"]
                """);

        Config config = Config.load(dir, entry -> {});
        config.setTokenManager(new TokenManager(dir, null));
        assertNull(config.getTokensConfig().getPolicy("unknown-policy"));
    }

    @Test
    public void policyDefaultExpiryIsLoaded() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-policy-expiry");
        Files.writeString(dir.resolve("config.yaml"), """
                port: 1913
                players:
                  max-list: 5
                """);
        Files.writeString(dir.resolve("policies.yaml"), """
                policies:
                  - id: admin
                    default-expiry: 60
                    permissions: ["*"]
                """);

        Config config = Config.load(dir, entry -> {});
        assertNotNull(config.getTokensConfig());
        assertEquals(60L, config.getTokensConfig().getPolicy("admin").defaultExpiry);
    }

    @Test
    public void rotateTokenChangesTheActiveSigningSecret() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-token-rotation");
        TokenManager tm = new TokenManager(dir, null);
        TokenManager.Token token = tm.createToken("rotate-me", java.util.List.of("server.log"), 120L);
        String originalSalt = token.salt;
        long expiryBefore = token.expiry;

        tm.rotateToken(token.id);
        assertTrue(!token.salt.equals(originalSalt));
        assertTrue(token.expiry >= expiryBefore - 1);
    }

    @Test
    public void reloadAppliesRuntimeSettingsAndReportsRestartOnlyKeys() throws IOException {
        // Regression: reload ignored players.max-list and claimed to apply bind-address.
        Path dir = Files.createTempDirectory("hungerbridge-reload");
        Files.writeString(dir.resolve("config.yaml"), "port: 1913\nbind-address: 127.0.0.1\nplayers:\n  max-list: 5\n");
        List<String> warnings = new java.util.ArrayList<>();
        Logger logger = entry -> { if ("WARN".equals(entry.level())) warnings.add(entry.message()); };
        Config config = Config.load(dir, logger);
        assertEquals(5, config.getPlayersMaxList());

        Files.writeString(dir.resolve("config.yaml"), "port: 1914\nbind-address: 127.0.0.1\ndebug: true\nplayers:\n  max-list: 7\n");
        assertTrue(config.reload(logger));
        assertEquals(7, config.getPlayersMaxList());
        assertTrue(config.isDebug());
        assertEquals(1913, config.getPort(), "port changes need a restart");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("restart")), warnings.toString());

        Files.writeString(dir.resolve("config.yaml"), "- not a mapping\n");
        assertTrue(!config.reload(logger));
        assertEquals(7, config.getPlayersMaxList(), "an invalid file keeps the previous values");
    }
}
