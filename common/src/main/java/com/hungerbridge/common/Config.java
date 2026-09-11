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
    private final Path configDir;
    private String bindAddress = "127.0.0.1";
    private java.util.List<String> allowedRemoteIps = java.util.List.of("127.0.0.1", "::1");
    private String ipMode = "whitelist";

    // Players config
    private final int playersMaxList;

    // JSON API metadata
    private String platform = "unknown";
    private String minecraftVersion = "unknown";
    private String bridgeVersion;
    private TokenManager tokenManager;
    // runtime debug flag (controls verbose HMAC/raw-body logging)
    private boolean debug = false;
    
    private com.hungerbridge.common.TokensConfig tokensConfig;

    public Config(
            int port,
            int playersMaxList,
            String bridgeVersion
    ) {
        this(port, playersMaxList, bridgeVersion, null);
    }

    public Config(
            int port,
            int playersMaxList,
            String bridgeVersion,
            Path configDir
    ) {
        this.port = port;
        this.playersMaxList = playersMaxList;
        this.bridgeVersion = bridgeVersion;
        this.configDir = configDir;
    }

    public int getPort() { return port; }

    public String getBindAddress() { return bindAddress; }

    public Path getConfigDir() { return configDir; }

    public java.util.List<String> getAllowedRemoteIps() { return allowedRemoteIps; }

    public String getIpMode() { return ipMode; }

    public boolean isRemoteAllowed(String remoteIp) {
        if (remoteIp == null || remoteIp.isBlank()) return false;
        String mode = this.ipMode == null ? "whitelist" : this.ipMode.trim().toLowerCase();
        // blacklist: deny only if the remote IP matches an entry in the list
        if ("blacklist".equals(mode)) {
            if (allowedRemoteIps == null || allowedRemoteIps.isEmpty()) return true;
            for (String blocked : allowedRemoteIps) {
                if (blocked != null && com.hungerbridge.common.security.IpMatcher.matches(blocked, remoteIp)) {
                    return false;
                }
            }
            return true;
        }
        // whitelist (default): allow only if an entry matches
        if (allowedRemoteIps == null || allowedRemoteIps.isEmpty()) return false;
        for (String allowed : allowedRemoteIps) {
            if (allowed != null && com.hungerbridge.common.security.IpMatcher.matches(allowed, remoteIp)) {
                return true;
            }
        }
        return false;
    }

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

                    // Seed the platform config directory from autogen templates bundled in the
                    // plugin JAR. Templates are used only to create missing runtime files;
                    // existing operator-modified runtime config is never overwritten.
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
            String bindAddress = String.valueOf(root.getOrDefault("bind-address", "127.0.0.1")).trim();
            if (bindAddress.isEmpty()) bindAddress = "127.0.0.1";
            boolean debug = Boolean.parseBoolean(String.valueOf(root.getOrDefault("debug", false)));

            java.util.List<String> allowedRemoteIps = loadAllowedIps(configDir, logger);
            String ipMode = loadIpMode(configDir, logger);

            // validate auxiliary configs and log status (log parsing errors from TokensConfig)
            com.hungerbridge.common.TokensConfig tc = com.hungerbridge.common.TokensConfig.load(configDir, logger);

            Map<String, Object> players = (Map<String, Object>) root.getOrDefault("players", new LinkedHashMap<>());

            int playersMaxList = ((Number) players.getOrDefault("max-list", 50)).intValue();

                Config cfg = new Config(
                    port,
                    playersMaxList,
                    bridgeVersion,
                    configDir
                );

                cfg.bindAddress = bindAddress;
                cfg.allowedRemoteIps = allowedRemoteIps;
                cfg.ipMode = ipMode;
                cfg.debug = debug;

                // attach parsed auxiliary configs
                cfg.setTokensConfig(tc != null ? tc : com.hungerbridge.common.TokensConfig.defaults());

                return cfg;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load HungerBridge config", e);
        }
    }

    public void setTokenManager(TokenManager tm) { this.tokenManager = tm; }
    public TokenManager getTokenManager() { return tokenManager; }

    public boolean isDebug() { return debug; }
    

    public void setTokensConfig(com.hungerbridge.common.TokensConfig tc) { this.tokensConfig = tc; }
    public com.hungerbridge.common.TokensConfig getTokensConfig() { return tokensConfig; }

    private static java.util.List<String> loadAllowedIps(Path configDir, Logger logger) {
        java.util.List<String> defaults = java.util.List.of("127.0.0.1", "::1");
        if (configDir == null) return defaults;
        Path securityFile = configDir.resolve("security.yaml");
        if (!Files.exists(securityFile)) return defaults;
        try (InputStream in = Files.newInputStream(securityFile)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) return defaults;
            Map<String, Object> root = (Map<String, Object>) loaded;
            Object ipsNode = root.get("ips");
            if (!(ipsNode instanceof Map)) return defaults;
            Map<String, Object> ips = (Map<String, Object>) ipsNode;
            Object listNode = ips.get("list");
            if (!(listNode instanceof java.util.List<?> list)) return defaults;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item == null) continue;
                String value = String.valueOf(item).trim();
                // allow explicit empty lists to be returned (caller will interpret mode)
                if (!value.isEmpty()) out.add(value);
            }
            return out;
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to parse security.yaml ips list: " + e.getMessage());
            return defaults;
        }
    }

    private static String loadIpMode(Path configDir, Logger logger) {
        if (configDir == null) return "whitelist";
        Path securityFile = configDir.resolve("security.yaml");
        if (!Files.exists(securityFile)) return "whitelist";
        try (InputStream in = Files.newInputStream(securityFile)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) return "whitelist";
            Map<String, Object> root = (Map<String, Object>) loaded;
            Object ipsNode = root.get("ips");
            if (!(ipsNode instanceof Map)) return "whitelist";
            Map<String, Object> ips = (Map<String, Object>) ipsNode;
            Object mode = ips.get("mode");
            return mode == null ? "whitelist" : String.valueOf(mode).trim();
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to parse security.yaml ips mode: " + e.getMessage());
            return "whitelist";
        }
    }

    private static void seedRuntimeConfigFromAutogen(Path runtimeConfigDir, Logger logger) throws IOException {
        java.util.List<String> copied = new java.util.ArrayList<>();

        // Load templates from the plugin JAR only. Use classpath resources under
        // /autogen/*. If no bundled templates are found, fall back to the programmatic
        // RuntimeConfigSeeder to create sensible defaults.
        boolean anyResourceFound = false;
        for (String fileName : java.util.List.of("config.yaml", "policies.yaml", "security.yaml")) {
            String resourcePath = "/autogen/" + fileName;
            java.net.URL resUrl = Config.class.getResource(resourcePath);
            if (resUrl == null) continue;
            anyResourceFound = true;

            Path target = runtimeConfigDir.resolve(fileName);
            if (Files.exists(target)) {
                // Do not overwrite operator-managed runtime config
                continue;
            }

            try (InputStream in = resUrl.openStream()) {
                Files.copy(in, target);
                copied.add(fileName);
            }
        }

        if (logger != null && !copied.isEmpty()) {
            logger.log("INFO", "Copied runtime config from bundled autogen templates: " + String.join(", ", copied));
        }

        if (!anyResourceFound) {
            if (logger != null) {
                logger.log("WARN", "No bundled autogen templates found in the JAR; HungerBridge will not generate runtime config. Operators must provide config/HungerBridge files or include autogen templates in the plugin JAR.");
            }
        }
    }

    /**
     * Reload runtime configuration values from disk into this Config instance.
     * Returns true on success, false on error (logs via provided logger).
     */
    @SuppressWarnings("unchecked")
    public synchronized boolean reload(Logger logger) {
        if (this.configDir == null) {
            if (logger != null) logger.log("WARN", "Config.reload called but configDir is null");
            return false;
        }
        try {
            boolean anyOk = false;

            // config.yaml
            Path configFile = this.configDir.resolve("config.yaml");
            if (!Files.exists(configFile)) {
                if (logger != null) logger.log("WARN", "config.yaml missing; skipping config reload.");
            } else {
                Yaml yaml = new Yaml();
                Map<String, Object> root;
                try (InputStream in = Files.newInputStream(configFile)) {
                    Object loaded = yaml.load(in);
                    if (!(loaded instanceof Map)) {
                        if (logger != null) logger.log("WARN", "Invalid config.yaml structure during reload");
                    } else {
                        root = (Map<String, Object>) loaded;
                        String bindAddress = String.valueOf(root.getOrDefault("bind-address", this.bindAddress)).trim();
                        if (bindAddress.isEmpty()) bindAddress = this.bindAddress;
                        boolean debug = Boolean.parseBoolean(String.valueOf(root.getOrDefault("debug", this.debug)));
                        this.bindAddress = bindAddress;
                        this.debug = debug;
                        anyOk = true;
                        if (logger != null) logger.log("INFO", "Reloaded: config.yaml");
                    }
                } catch (Exception e) {
                    if (logger != null) logger.log("WARN", "Failed to reload config.yaml: " + e.getMessage());
                }
            }

            // security.yaml
            Path securityFile = this.configDir.resolve("security.yaml");
            if (!Files.exists(securityFile)) {
                if (logger != null) logger.log("INFO", "security.yaml not present; using defaults.");
            } else {
                try (InputStream in = Files.newInputStream(securityFile)) {
                    Object loaded = new Yaml().load(in);
                    if (!(loaded instanceof Map)) {
                        if (logger != null) logger.log("WARN", "Invalid security.yaml structure; using defaults.");
                    } else {
                        // refresh allowed ips and ip mode
                        java.util.List<String> allowedRemoteIps = loadAllowedIps(this.configDir, logger);
                        String ipMode = loadIpMode(this.configDir, logger);
                        this.allowedRemoteIps = allowedRemoteIps == null ? java.util.List.of() : allowedRemoteIps;
                        this.ipMode = ipMode == null ? this.ipMode : ipMode;
                        anyOk = true;
                        if (logger != null) logger.log("INFO", "Reloaded: security.yaml");
                    }
                } catch (Exception e) {
                    if (logger != null) logger.log("WARN", "Failed to reload security.yaml: " + e.getMessage());
                }
            }

            // policies.yaml (TokensConfig)
            Path policiesFile = this.configDir.resolve("policies.yaml");
            try {
                com.hungerbridge.common.TokensConfig tc = com.hungerbridge.common.TokensConfig.load(this.configDir, logger);
                this.setTokensConfig(tc != null ? tc : com.hungerbridge.common.TokensConfig.defaults());
                if (Files.exists(policiesFile)) {
                    if (logger != null) logger.log("INFO", "Reloaded: policies.yaml");
                    anyOk = true;
                } else {
                    if (logger != null) logger.log("INFO", "policies.yaml not present; using defaults.");
                }
            } catch (Exception e) {
                if (logger != null) logger.log("WARN", "Failed to reload policies.yaml: " + e.getMessage());
            }

            return anyOk;
        } catch (Exception e) {
            if (logger != null) logger.log("WARN", "Failed to reload config: " + e.getMessage());
            return false;
        }
    }

}
