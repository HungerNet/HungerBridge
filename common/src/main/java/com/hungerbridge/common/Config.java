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

            // validate auxiliary configs and log status
            com.hungerbridge.common.TokensConfig tc = null;
            try {
                tc = com.hungerbridge.common.TokensConfig.load(configDir);
                if (logger != null) logger.log("INFO", "Loaded token policies");
            } catch (Exception ignored) {}

            Map<String, Object> players = (Map<String, Object>) root.getOrDefault("players", new LinkedHashMap<>());

            int playersMaxList = ((Number) players.getOrDefault("max-list", 50)).intValue();

                Config cfg = new Config(
                    port,
                    playersMaxList,
                    bridgeVersion
                );

                // attach parsed auxiliary configs
                cfg.setTokensConfig(tc != null ? tc : com.hungerbridge.common.TokensConfig.defaults());

                return cfg;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load HungerBridge config", e);
        }
    }

    public void setTokenManager(TokenManager tm) { this.tokenManager = tm; }
    public TokenManager getTokenManager() { return tokenManager; }
    

    public void setTokensConfig(com.hungerbridge.common.TokensConfig tc) { this.tokensConfig = tc; }
    public com.hungerbridge.common.TokensConfig getTokensConfig() { return tokensConfig; }

    private static void seedRuntimeConfigFromAutogen(Path runtimeConfigDir, Logger logger) throws IOException {
        Path autogenRoot = findAutogenTemplateDir();
        if (autogenRoot == null || !Files.exists(autogenRoot)) {
            if (logger != null) {
                logger.log("WARN", "No autogen/HungerBridge templates found; creating runtime defaults in the active config directory.");
            }
            com.hungerbridge.common.config.RuntimeConfigSeeder.seed(runtimeConfigDir);
            return;
        }

        java.util.List<String> copied = new java.util.ArrayList<>();
        for (String fileName : java.util.List.of("config.yaml", "policies.yaml")) {
            Path source = autogenRoot.resolve(fileName);
            Path target = runtimeConfigDir.resolve(fileName);
            if (!Files.exists(source)) continue;
            if (Files.exists(target)) {
                Files.delete(target);
            }
            Files.copy(source, target);
            copied.add(fileName);
        }

        if (logger != null && !copied.isEmpty()) {
            logger.log("INFO", "Copied runtime config from autogen/HungerBridge: " + String.join(", ", copied));
        }
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

        // Also attempt to locate autogen relative to the code location (useful when
        // the JVM working directory is not the project root, e.g. when running from
        // a container or a different process cwd).
        try {
            java.net.URI codeUri = Config.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codeLoc = Path.of(codeUri).toAbsolutePath();
            Path cur = codeLoc;
            for (int i = 0; i < 6 && cur != null; i++) {
                Path candidate = cur.resolve("autogen").resolve("HungerBridge");
                candidates.add(candidate);
                cur = cur.getParent();
            }
        } catch (Exception ignored) {}

        // Common repo layout fallback
        try {
            candidates.add(Path.of("/home/container/HungerBridge").resolve("autogen").resolve("HungerBridge"));
        } catch (Exception ignored) {}

        for (Path candidate : candidates) {
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        return null;
    }

}
