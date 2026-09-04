package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class ConfigTest {

    @Test
    public void readsEnabledEndpointsAndNewSecuritySchema() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-config");
        Path configFile = dir.resolve("config.yaml");
        Files.writeString(configFile, """
                port: 1913
                enabled_endpoints:
                  run: true
                  log: true
                  ping: true
                  stream_logs: true
                  info: true
                  status: true
                  tps: true
                  players: true
                players:
                  max-list: 10
                """);

        Path securityFile = dir.resolve("security.yaml");
        Files.writeString(securityFile, """
                ip_list:
                  mode: blacklist
                  list: ["10.0.0.0/8"]
                rate_limits:
                  token_rps: 5.0
                  token_burst: 10.0
                  ip_rps: 20.0
                  ip_burst: 40.0
                audit_retention_days: 14
                """);

        Config config = Config.load(dir, (level, message) -> {
        });

        assertTrue(config.isStreamLogsEnabled());
        assertTrue(config.getSecurityConfig() != null);
        assertTrue(config.getSecurityConfig().ipBlacklist.contains("10.0.0.0/8"));
    }

    @Test
    public void reloadConfigLoadsNamedTokenPolicies() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-reload");
        Files.writeString(dir.resolve("config.yaml"), """
                port: 1913
                enabled_endpoints:
                  run: true
                  log: true
                  ping: true
                  stream_logs: true
                  info: true
                  status: true
                  tps: true
                  players: true
                players:
                  max-list: 10
                """);
        Files.writeString(dir.resolve("security.yaml"), """
                ip_list:
                  mode: blacklist
                  list: []
                rate_limits:
                  token_rps: 5
                  token_burst: 10
                  ip_rps: 20
                  ip_burst: 40
                audit_retention_days: 14
                """);
        Files.writeString(dir.resolve("tokens.yaml"), """
                tokens:
                  - id: admin
                    default_expiry: 0
                    max_skew: -1
                    endpoints_mode: blacklist
                    endpoints: []
                    commands_mode: blacklist
                    commands: []
                  - id: reporter
                    default_expiry: 3600
                    max_skew: 120
                    endpoints_mode: whitelist
                    endpoints: ["ping", "info"]
                    commands_mode: blacklist
                    commands: []
                """);

        Config config = Config.load(dir, (level, message) -> {});
        config.setTokenManager(new TokenManager(dir, null));
        AdminService admin = new AdminService(dir, config, null, null);
        assertNotNull(config.getTokensConfig());
        assertTrue(config.getTokensConfig().getPolicy("reporter") != null);

        Files.writeString(dir.resolve("tokens.yaml"), """
                tokens:
                  - id: admin
                    default_expiry: 0
                    max_skew: -1
                    endpoints_mode: blacklist
                    endpoints: []
                    commands_mode: blacklist
                    commands: []
                  - id: watcher
                    default_expiry: 1200
                    max_skew: 60
                    endpoints_mode: whitelist
                    endpoints: ["stream_logs"]
                    commands_mode: blacklist
                    commands: []
                """);

        assertTrue(admin.reloadConfig());
        assertNotNull(config.getTokensConfig().getPolicy("watcher"));
        assertTrue(config.getTokensConfig().getPolicy("watcher").defaultExpirySeconds == 1200L);
        assertTrue(config.getTokensConfig().getPolicy("reporter") == null);
    }

    @Test
    public void rejectsUnknownTokenPolicyIdOnCreate() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-unknown-token");
        Files.writeString(dir.resolve("config.yaml"), """
                port: 1913
                enabled_endpoints:
                  run: true
                  log: true
                  ping: true
                  stream_logs: true
                  info: true
                  status: true
                  tps: true
                  players: true
                players:
                  max-list: 10
                """);
        Files.writeString(dir.resolve("security.yaml"), """
                ip_list:
                  mode: blacklist
                  list: []
                rate_limits:
                  token_rps: 5
                  token_burst: 10
                  ip_rps: 20
                  ip_burst: 40
                audit_retention_days: 14
                """);
        Files.writeString(dir.resolve("tokens.yaml"), """
                tokens:
                  - id: admin
                    default_expiry: 0
                    max_skew: -1
                    endpoints_mode: blacklist
                    endpoints: []
                    commands_mode: blacklist
                    commands: []
                """);

        Config config = Config.load(dir, (level, message) -> {});
        config.setTokenManager(new TokenManager(dir, null));
        AdminService admin = new AdminService(dir, config, null, null);

        assertNull(admin.createToken("unknown-policy", null, 0L, java.util.List.of(), java.util.List.of()));
    }

    @Test
    public void commandMessagesUseConsistentCapitalization() {
        assertTrue(CommandMessages.HEADER.startsWith("HungerBridge Commands"));
        assertTrue(CommandMessages.helpLines().getFirst().startsWith("Usage:"));
        assertTrue(CommandMessages.createdToken("admin", "secret").startsWith("Created token"));
        assertTrue(CommandMessages.rotatedToken("admin", "secret").startsWith("Rotated token"));
    }
}
