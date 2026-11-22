package com.hungerbridge.common;

import com.hungerbridge.common.security.IpMatcher;
import com.hungerbridge.common.security.TokenManager;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration from {@code config.yaml}, {@code security.yaml} and
 * {@code policies.yaml} in the platform's config directory. Missing files are created from
 * the templates bundled at {@code /autogen/*}; existing files are never overwritten.
 *
 * <p>{@link #load} and {@link #reload} share one parser. A reload applies everything that can
 * change at runtime (debug flag, player list cap, IP filter, rate limits, policies);
 * {@code port} and {@code bind-address} need a restart and are reported as such.
 */
public final class Config {

    static final List<String> DEFAULT_ALLOWED_IPS = List.of("127.0.0.1", "::1");

    private final Path configDir;
    private final int port;
    private final String bindAddress;

    private volatile int playersMaxList;
    private volatile boolean debug;
    private volatile List<String> allowedRemoteIps = DEFAULT_ALLOWED_IPS;
    private volatile String ipMode = "whitelist";
    private volatile TokenManager.RateLimitSettings rateLimitSettings = TokenManager.loadRateLimitSettings(null);
    private volatile TokensConfig tokensConfig = TokensConfig.defaults();

    // set by the platform after loading
    private String platform = "unknown";
    private String minecraftVersion = "unknown";
    private String bridgeVersion = "unknown";
    private TokenManager tokenManager;

    private Config(Path configDir, int port, String bindAddress) {
        this.configDir = configDir;
        this.port = port;
        this.bindAddress = bindAddress;
    }

    public static Config load(Path configDir, Logger logger) {
        try {
            Files.createDirectories(configDir);
            seedRuntimeConfigFromAutogen(configDir, logger);
            Map<String, Object> main = readYaml(configDir.resolve("config.yaml"));
            if (main == null) throw new IllegalStateException("config.yaml must be a YAML mapping");
            int port = ((Number) main.getOrDefault("port", 1913)).intValue();
            String bind = String.valueOf(main.getOrDefault("bind-address", "127.0.0.1")).trim();
            Config cfg = new Config(configDir, port, bind.isEmpty() ? "127.0.0.1" : bind);
            cfg.apply(main, logger);
            return cfg;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load HungerBridge config from " + configDir, e);
        }
    }

    /** Re-reads the files. Returns false (and keeps the previous values) if a file is invalid. */
    public synchronized boolean reload(Logger logger) {
        Map<String, Object> main;
        try {
            main = readYaml(configDir.resolve("config.yaml"));
        } catch (Exception e) {
            if (logger != null) logger.warn("config.yaml could not be read: " + e.getMessage());
            return false;
        }
        if (main == null) {
            if (logger != null) logger.warn("config.yaml must be a YAML mapping; nothing was reloaded");
            return false;
        }
        int newPort = ((Number) main.getOrDefault("port", port)).intValue();
        String newBind = String.valueOf(main.getOrDefault("bind-address", bindAddress)).trim();
        if (newPort != port || !newBind.equals(bindAddress)) {
            if (logger != null) logger.warn("port and bind-address changes take effect after a server restart");
        }
        apply(main, logger);
        if (logger != null) logger.info("Configuration reloaded");
        return true;
    }

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> main, Logger logger) {
        debug = Boolean.parseBoolean(String.valueOf(main.getOrDefault("debug", false)));
        Object players = main.get("players");
        playersMaxList = players instanceof Map<?, ?> p && p.get("max-list") instanceof Number n ? n.intValue() : 50;

        List<String> ips = DEFAULT_ALLOWED_IPS;
        String mode = "whitelist";
        try {
            Map<String, Object> security = readYaml(configDir.resolve("security.yaml"));
            if (security != null && security.get("ips") instanceof Map<?, ?> ipsNode) {
                if (ipsNode.get("mode") != null) mode = String.valueOf(ipsNode.get("mode")).trim();
                if (ipsNode.get("list") instanceof List<?> list) {
                    ips = new ArrayList<>();
                    for (Object item : list) {
                        String value = item == null ? "" : String.valueOf(item).trim();
                        if (!value.isEmpty()) ips.add(value);
                    }
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.warn("security.yaml could not be read, using localhost-only defaults: " + e.getMessage());
        }
        allowedRemoteIps = List.copyOf(ips);
        ipMode = mode;
        rateLimitSettings = TokenManager.loadRateLimitSettings(configDir);
        tokensConfig = TokensConfig.load(configDir, logger);
        if (tokenManager != null) tokenManager.setDebug(debug);
    }

    /** The YAML mapping in {@code file}, {@code null} if the file is missing or not a mapping. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            return loaded instanceof Map ? (Map<String, Object>) loaded : null;
        }
    }

    private static void seedRuntimeConfigFromAutogen(Path dir, Logger logger) throws IOException {
        List<String> copied = new ArrayList<>();
        for (String fileName : List.of("config.yaml", "policies.yaml", "security.yaml")) {
            java.net.URL template = Config.class.getResource("/autogen/" + fileName);
            Path target = dir.resolve(fileName);
            if (template == null || Files.exists(target)) continue;
            try (InputStream in = template.openStream()) {
                Files.copy(in, target);
                copied.add(fileName);
            }
        }
        if (logger != null && !copied.isEmpty()) logger.info("Created default " + String.join(", ", copied) + " in " + dir);
    }

    public boolean isRemoteAllowed(String remoteIp) {
        if (remoteIp == null || remoteIp.isBlank()) return false;
        List<String> list = allowedRemoteIps;
        boolean listed = list.stream().anyMatch(entry -> IpMatcher.matches(entry, remoteIp));
        return "blacklist".equals(ipMode.toLowerCase(Locale.ROOT)) ? !listed : listed;
    }

    public Path getConfigDir() { return configDir; }
    public int getPort() { return port; }
    public String getBindAddress() { return bindAddress; }
    public int getPlayersMaxList() { return playersMaxList; }
    public boolean isDebug() { return debug; }
    public List<String> getAllowedRemoteIps() { return allowedRemoteIps; }
    public String getIpMode() { return ipMode; }
    public TokenManager.RateLimitSettings getRateLimitSettings() { return rateLimitSettings; }
    public TokensConfig getTokensConfig() { return tokensConfig; }

    public String getPlatform() { return platform; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public String getVersion() { return bridgeVersion; }
    public TokenManager getTokenManager() { return tokenManager; }

    public void setPlatform(String platform) { this.platform = platform; }
    public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = minecraftVersion; }
    public void setBridgeVersion(String bridgeVersion) { this.bridgeVersion = bridgeVersion; }

    public void setTokenManager(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
        if (tokenManager != null) tokenManager.setDebug(debug);
    }
}
