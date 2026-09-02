package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration holder for HungerBridge.
 *
 * YAML schema produced / consumed:
 *
 * port: 1913
 *
 * auth:
 *   key: <uuid>
 *
 * enabled_endpoints:
 *   run: true
 *   log: true
 *   ping: true
 *   stream_logs: true
 *   info: true
 *   status: true
 *   tps: true
 *   players: true
 *
 * players:
 *   max-list: 50
 */
public final class Config {

    private final int port;
    private final String authKey;

    // endpoint toggles
    private final boolean runEnabled;
    private final boolean logEnabled;
    private final boolean pingEnabled;
            // Load version.yaml
            String bridgeVersion = "unknown";
            try {
                Path versionFile = configDir.getParent().getParent().resolve("version.yaml");
                if (Files.exists(versionFile)) {
                    Yaml vyaml = new Yaml();
                    try (InputStream vin = Files.newInputStream(versionFile)) {
                        Object vloaded = vyaml.load(vin);
                        if (vloaded instanceof Map) {
                            Map<String, Object> vroot = (Map<String, Object>) vloaded;
                            bridgeVersion = (String) vroot.getOrDefault("version", "unknown");
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Generate default config and auxiliary files if config.yaml missing
            if (!Files.exists(configFile)) {
                String cfg = "port: 1913\n\n" +
                        "enabled_endpoints:\n" +
                        "  run: true\n" +
                        "  log: true\n" +
                        "  ping: true\n" +
                        "  stream_logs: true\n" +
                        "  info: true\n" +
                        "  status: true\n" +
                        "  tps: true\n" +
                        "  players: true\n\n" +
                        "players:\n" +
                        "  max-list: 50\n";

                try (OutputStream out = Files.newOutputStream(configFile);
                     OutputStreamWriter writer = new OutputStreamWriter(out)) {
                    writer.write(cfg);
                } catch (Exception ignored) {}

                try {
                    Path security = configDir.resolve("security.yaml");
                    if (!Files.exists(security)) {
                        String sec = "self_probe: false\n" +
                                "public_base_url: 'https://bridge.example.com'\n" +
                                "probe_timeout_ms: 2000\n\n" +
                                "ip_whitelist: []\n" +
                                "ip_blacklist: []\n\n" +
                                "rate_limits:\n" +
                                "  token_rps: 5.0\n" +
                                "  token_burst: 10.0\n" +
                                "  ip_rps: 20.0\n" +
                                "  ip_burst: 40.0\n\n" +
                                "audit_retention_days: 14\n";
                        Files.writeString(security, sec);
                    }
                } catch (Exception ignored) {}

                try {
                    Path commands = configDir.resolve("commands.yaml");
                    if (!Files.exists(commands)) {
                        String cc = "enable_commands: true\n" +
                                "enable_admin_http: true\n" +
                                "command_aliases: []\n\n" +
                                "token_defaults:\n" +
                                "  ttl: 3600\n" +
                                "  whitelist: []\n" +
                                "  blacklist: []\n\n" +
                                "global_whitelist: []\n" +
                                "global_blacklist: []\n";
                        Files.writeString(commands, cc);
                    }
                } catch (Exception ignored) {}

                try {
                    Path readme = configDir.resolve("README.md");
                    if (!Files.exists(readme)) {
                        String r = "# HungerBridge configuration\n\n" +
                                "This directory contains runtime configuration and storage for HungerBridge.\n\n" +
                                "Files and directories:\n" +
                                "- config.yaml: core server config (port, enabled endpoints, players)\n" +
                                "- security.yaml: security settings, IP allow/deny and rate limits\n" +
                                "- commands.yaml: controls in-game commands and admin HTTP enabling\n" +
                                "- storage/: tokens.json and sessions.json (managed by the server)\n" +
                                "- logs/: daily audit logs (JSON lines)\n\n" +
                                "Token management:\n" +
                                "- Tokens are stored in storage/tokens.json. Use the admin HTTP endpoints or in-game /hb tokens commands to create, list, revoke, or rotate tokens.\n\n" +
                                "Audit logs:\n" +
                                "- Audit events are appended to logs/YYYY-MM-DD.audit.log. Configure retention in security.yaml via `audit_retention_days`.\n\n" +
                                "Do NOT check secrets into source control.\n";
                        Files.writeString(readme, r);
                    }
                } catch (Exception ignored) {}
            }

            // Generate default config and auxiliary files if missing
            if (!Files.exists(configFile)) {
                String cfg = "port: 1913\n\n" +
                        "enabled_endpoints:\n" +
                        "  run: true\n" +
                        "  log: true\n" +
                        "  ping: true\n" +
                        "  stream_logs: true\n" +
                        "  info: true\n" +
                        "  status: true\n" +
                        "  tps: true\n" +
                        "  players: true\n\n" +
                        "players:\n" +
                        "  max-list: 50\n";

                try (OutputStream out = Files.newOutputStream(configFile);
                     OutputStreamWriter writer = new OutputStreamWriter(out)) {
                    writer.write(cfg);
                }

                try {
                    Path security = configDir.resolve("security.yaml");
                    if (!Files.exists(security)) {
                        String sec = "self_probe: false\n" +
                                "public_base_url: 'https://bridge.example.com'\n" +
                                "probe_timeout_ms: 2000\n\n" +
                                "ip_whitelist: []\n" +
                                "ip_blacklist: []\n\n" +
                                "rate_limits:\n" +
                                "  token_rps: 5.0\n" +
                                "  token_burst: 10.0\n" +
                                "  ip_rps: 20.0\n" +
                                "  ip_burst: 40.0\n\n" +
                                "audit_retention_days: 14\n";
                        Files.writeString(security, sec);
                    }
                } catch (Exception ignored) {}

                try {
                    Path commands = configDir.resolve("commands.yaml");
                    if (!Files.exists(commands)) {
                        String cc = "enable_commands: true\n" +
                                "enable_admin_http: true\n" +
                                "command_aliases: []\n\n" +
                                "token_defaults:\n" +
                                "  ttl: 3600\n" +
                                "  whitelist: []\n" +
                                "  blacklist: []\n\n" +
                                "global_whitelist: []\n" +
                                "global_blacklist: []\n";
                        Files.writeString(commands, cc);
                    }
                } catch (Exception ignored) {}

                try {
                    Path readme = configDir.resolve("README.md");
                    if (!Files.exists(readme)) {
                        String r = "# HungerBridge configuration\n\n" +
                                "This directory contains runtime configuration and storage for HungerBridge.\n\n" +
                                "Files and directories:\n" +
                                "- config.yaml: core server config (port, enabled endpoints, players)\n" +
                                "- security.yaml: security settings, IP allow/deny and rate limits\n" +
                                "- commands.yaml: controls in-game commands and admin HTTP enabling\n" +
                                "- storage/: tokens.json and sessions.json (managed by the server)\n" +
                                "- logs/: daily audit logs (JSON lines)\n\n" +
                                "Token management:\n" +
                                "- Tokens are stored in storage/tokens.json. Use the admin HTTP endpoints or in-game /hb tokens commands to create, list, revoke, or rotate tokens.\n\n" +
                                "Audit logs:\n" +
                                "- Audit events are appended to logs/YYYY-MM-DD.audit.log. Configure retention in security.yaml via `audit_retention_days`.\n\n" +
                                "Do NOT check secrets into source control.\n";
                        Files.writeString(readme, r);
                    }
                } catch (Exception ignored) {}
            }

            // Load config.yaml
            Yaml yaml = new Yaml();
            Map<String, Object> root;
            try (InputStream in = Files.newInputStream(configFile)) {
                Object loaded = yaml.load(in);
                if (!(loaded instanceof Map)) {
                    throw new IllegalStateException("Invalid config.yaml structure");
                }
                root = (Map<String, Object>) loaded;
            }

            int port = ((Number) root.getOrDefault("port", 1913)).intValue();

            Map<String, Object> auth = (Map<String, Object>) root.getOrDefault("auth", new LinkedHashMap<>());
            String authKey = (String) auth.getOrDefault("key", "");

            Map<String, Object> enabledEndpoints = (Map<String, Object>) root.getOrDefault(
                    "enabled_endpoints",
                    root.getOrDefault("endpoints", new LinkedHashMap<>())
            );
            Map<String, Object> players = (Map<String, Object>) root.getOrDefault("players", new LinkedHashMap<>());

            boolean run = coerceBoolean(enabledEndpoints.getOrDefault("run", true));
            boolean log = coerceBoolean(enabledEndpoints.getOrDefault("log", true));
            boolean ping = coerceBoolean(enabledEndpoints.getOrDefault("ping", true));
            boolean streamLogs = coerceBoolean(enabledEndpoints.getOrDefault("stream_logs", true));
            boolean info = coerceBoolean(enabledEndpoints.getOrDefault("info", true));
            boolean status = coerceBoolean(enabledEndpoints.getOrDefault("status", true));
            boolean tps = coerceBoolean(enabledEndpoints.getOrDefault("tps", true));
            boolean playersEnabled = coerceBoolean(enabledEndpoints.getOrDefault("players", true));

            int playersMaxList = ((Number) players.getOrDefault("max-list", 50)).intValue();

            return new Config(
                    port,
                    authKey,
                    run,
                    log,
                    ping,
                    streamLogs,
                    info,
                    status,
                    tps,
                    playersEnabled,
                    playersMaxList,
                    bridgeVersion
            );

        } catch (IOException e) {
            throw new RuntimeException("Failed to load HungerBridge config", e);
        }
    }

    public void setTokenManager(TokenManager tm) { this.tokenManager = tm; }
    public TokenManager getTokenManager() { return tokenManager; }
    public void setRateLimiter(com.hungerbridge.common.security.RateLimiter rl) { this.rateLimiter = rl; }
    public com.hungerbridge.common.security.RateLimiter getRateLimiter() { return rateLimiter; }
    public void setAuditLogger(com.hungerbridge.common.log.AuditLogger al) { this.auditLogger = al; }
    public com.hungerbridge.common.log.AuditLogger getAuditLogger() { return auditLogger; }
    public void setSecurityConfig(com.hungerbridge.common.security.SecurityConfig sc) { this.securityConfig = sc; }
    public com.hungerbridge.common.security.SecurityConfig getSecurityConfig() { return securityConfig; }

    public void setCommandsConfig(com.hungerbridge.common.CommandsConfig cc) { this.commandsConfig = cc; }
    public com.hungerbridge.common.CommandsConfig getCommandsConfig() { return commandsConfig; }

    private static boolean coerceBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
