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
        this.port = port;
        this.playersMaxList = playersMaxList;
        this.bridgeVersion = bridgeVersion;
    }

    public int getPort() { return port; }

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
            boolean debug = Boolean.parseBoolean(String.valueOf(root.getOrDefault("debug", false)));

            // validate auxiliary configs and log status (log parsing errors from TokensConfig)
            com.hungerbridge.common.TokensConfig tc = com.hungerbridge.common.TokensConfig.load(configDir, logger);

            Map<String, Object> players = (Map<String, Object>) root.getOrDefault("players", new LinkedHashMap<>());

            int playersMaxList = ((Number) players.getOrDefault("max-list", 50)).intValue();

                Config cfg = new Config(
                    port,
                    playersMaxList,
                    bridgeVersion
                );

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

}
