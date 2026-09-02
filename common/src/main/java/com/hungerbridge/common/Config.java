package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;

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
 */
public final class Config {

    private final int port;
    private final String authKey;

    // endpoint toggles
    private final boolean runEnabled;
    private final boolean logEnabled;
    private final boolean pingEnabled;
    private final boolean streamLogsEnabled;
    private final boolean infoEnabled;
    private final boolean statusEnabled;
    private final boolean tpsEnabled;
    private final boolean playersEnabled;

    // Players config
    private final int playersMaxList;

    // JSON API metadata
    private String platform = "unknown";
    private String minecraftVersion = "unknown";
    private String bridgeVersion;
    private TokenManager tokenManager;
    private com.hungerbridge.common.security.RateLimiter rateLimiter;
    private com.hungerbridge.common.log.AuditLogger auditLogger;
    private com.hungerbridge.common.security.SecurityConfig securityConfig;
    private com.hungerbridge.common.CommandsConfig commandsConfig;

    public Config(
            int port,
            String authKey,
            boolean runEnabled,
            boolean logEnabled,
            boolean pingEnabled,
            boolean streamLogsEnabled,
            boolean infoEnabled,
            boolean statusEnabled,
            boolean tpsEnabled,
            boolean playersEnabled,
            int playersMaxList,
            String bridgeVersion
    ) {
        this.port = port;
        this.authKey = authKey;

        this.runEnabled = runEnabled;
        this.logEnabled = logEnabled;
        this.pingEnabled = pingEnabled;
        this.streamLogsEnabled = streamLogsEnabled;
        this.infoEnabled = infoEnabled;
        this.statusEnabled = statusEnabled;
        this.tpsEnabled = tpsEnabled;
        this.playersEnabled = playersEnabled;

        this.playersMaxList = playersMaxList;
        this.bridgeVersion = bridgeVersion;
    }

    public int getPort() { return port; }
    public String getAuthKey() { return authKey; }

    public boolean isRunEnabled() { return runEnabled; }
    public boolean isLogEnabled() { return logEnabled; }
    public boolean isPingEnabled() { return pingEnabled; }
    public boolean isStreamLogsEnabled() { return streamLogsEnabled; }
    public boolean isInfoEnabled() { return infoEnabled; }
    public boolean isStatusEnabled() { return statusEnabled; }
    public boolean isTpsEnabled() { return tpsEnabled; }
    public boolean isPlayersEnabled() { return playersEnabled; }

    public int getPlayersMaxList() { return playersMaxList; }

    public String getVersion() { return bridgeVersion; }
    public String getPlatform() { return platform; }
    public String getMinecraftVersion() { return minecraftVersion; }

    public void setPlatform(String platform) { this.platform = platform; }
    public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = minecraftVersion; }
    public void setBridgeVersion(String bridgeVersion) { this.bridgeVersion = bridgeVersion; }

    @SuppressWarnings("unchecked")
    public static Config load(Path configDir, Logger logger) {
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            Path configFile = configDir.resolve("config.yaml");

            // Load version.yaml (best-effort)
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

            // Seed the platform config directory from the checked-in autogen templates.
            // This keeps runtime config generation centralized in autogen/HungerBridge and
            // prevents stray repo-level config/ folders from being treated as live state.
            seedRuntimeConfigFromAutogen(configDir, logger);

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

            // validate auxiliary configs and log status
            try {
                com.hungerbridge.common.security.SecurityConfig sc = com.hungerbridge.common.security.SecurityConfig.load(configDir);
                if (logger != null) logger.log("INFO", "Loaded security.yaml (self_probe=" + sc.selfProbe + ")");
            } catch (Exception e) {
                if (logger != null) logger.log("WARN", "Failed to parse security.yaml: " + e.getMessage());
            }
            try {
                com.hungerbridge.common.CommandsConfig cc = com.hungerbridge.common.CommandsConfig.load(configDir);
                if (logger != null) logger.log("INFO", "Loaded commands.yaml (enable_commands=" + cc.enableCommands + ")");
            } catch (Exception e) {
                if (logger != null) logger.log("WARN", "Failed to parse commands.yaml: " + e.getMessage());
            }

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

    private static void seedRuntimeConfigFromAutogen(Path runtimeConfigDir, Logger logger) throws IOException {
        Path autogenRoot = findAutogenTemplateDir();
        boolean copiedAny = false;
        java.util.List<String> created = new java.util.ArrayList<>();
        java.util.List<String> existed = new java.util.ArrayList<>();

        if (autogenRoot != null && Files.exists(autogenRoot)) {
            for (String fileName : java.util.List.of("config.yaml", "commands.yaml", "security.yaml", "tokens.yaml")) {
                Path source = autogenRoot.resolve(fileName);
                Path target = runtimeConfigDir.resolve(fileName);
                if (!Files.exists(source)) continue;
                if (!Files.exists(target)) {
                    Files.copy(source, target);
                    created.add(fileName);
                    copiedAny = true;
                } else {
                    existed.add(fileName);
                }
            }
        }

        if (!copiedAny && (autogenRoot == null || !Files.exists(autogenRoot))) {
            if (logger != null) {
                logger.log("WARN", "No autogen/HungerBridge templates found; creating runtime defaults in the active config directory.");
            }
            writeFallbackRuntimeConfigs(runtimeConfigDir);
            return;
        }

        if (logger != null) {
            if (!created.isEmpty()) logger.log("INFO", "Seeded runtime config from autogen/HungerBridge: " + String.join(", ", created));
            if (!existed.isEmpty()) logger.log("INFO", "Runtime config already present: " + String.join(", ", existed));
        }
    }

    private static void writeFallbackRuntimeConfigs(Path runtimeConfigDir) throws IOException {
        Files.createDirectories(runtimeConfigDir);

        writeIfMissing(runtimeConfigDir.resolve("config.yaml"), "port: 1913\n\nauth:\n  key: \"CHANGE_ME\"\n\nenabled_endpoints:\n  run: true\n  log: true\n  ping: true\n  stream_logs: true\n  info: true\n  status: true\n  tps: true\n  players: true\n\nplayers:\n  max-list: 50\n");
        writeIfMissing(runtimeConfigDir.resolve("security.yaml"), "self_probe: true\npublic_base_url: \"https://my-proxy.example.com\"\nprobe_timeout_ms: 1500\n\nip_whitelist: []\nip_blacklist: []\n\nrate_limits:\n  token_rps: 5.0\n  token_burst: 10.0\n  ip_rps: 20.0\n  ip_burst: 40.0\n\naudit_retention_days: 14\n");
        writeIfMissing(runtimeConfigDir.resolve("commands.yaml"), "enable_commands: true\nenable_admin_http: true\ncommand_aliases:\n  - hb\n  - hungerbridge\ntoken_defaults:\n  ttl: 3600\n  whitelist: []\n  blacklist: []\nglobal_whitelist: []\nglobal_blacklist: []\n");
        writeIfMissing(runtimeConfigDir.resolve("tokens.yaml"), "allowed_skew_seconds: 300\ndefault_token_ttl_seconds: 0\n\n# tokens:\n#   - id: exampletokenid\n#     revoked: false\n#     expiry: 0\n#     whitelist:\n#       - run\n#     blacklist: []\n");
    }

    private static void writeIfMissing(Path path, String content) throws IOException {
        if (Files.exists(path)) return;
        Files.writeString(path, content);
    }

    private static Path findAutogenTemplateDir() {
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        Path userDir = Path.of(System.getProperty("user.dir", "")).toAbsolutePath();
        Path parent = userDir.getParent();

        candidates.add(userDir.resolve("autogen").resolve("HungerBridge"));
        candidates.add(userDir.resolve("HungerBridge").resolve("autogen").resolve("HungerBridge"));
        if (parent != null) {
            candidates.add(parent.resolve("HungerBridge").resolve("autogen").resolve("HungerBridge"));
            candidates.add(parent.resolve("autogen").resolve("HungerBridge"));
        }
        candidates.add(Path.of(".").toAbsolutePath().resolve("autogen").resolve("HungerBridge"));

        for (Path candidate : candidates) {
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        return null;
    }

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
