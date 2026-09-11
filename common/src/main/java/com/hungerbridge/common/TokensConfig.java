package com.hungerbridge.common;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TokensConfig {
    public final Map<String, TokenPolicy> policies = new LinkedHashMap<>();

    public static final class TokenPolicy {
        public String id = "admin";
        public final List<String> permissions = new ArrayList<>();
    }

    public TokenPolicy getPolicy(String policyId) {
        if (policyId == null || policyId.isBlank()) return null;
        return policies.get(policyId);
    }

    public boolean hasPolicy(String policyId) {
        return policyId != null && !policyId.isBlank() && policies.containsKey(policyId);
    }

    @SuppressWarnings("unchecked")
    public static TokensConfig load(Path configDir) {
        return load(configDir, null);
    }

    @SuppressWarnings("unchecked")
    public static TokensConfig load(Path configDir, com.hungerbridge.common.Logger logger) {
        TokensConfig config = defaults();
        Path policiesFile = configDir.resolve("policies.yaml");
        if (!Files.exists(policiesFile)) {
            return config;
        }
        try (InputStream in = Files.newInputStream(policiesFile)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                if (logger != null) logger.log("WARN", "Invalid policies.yaml structure; expected mapping at root.");
                return config;
            }
            Map<String, Object> root = (Map<String, Object>) loaded;
            Object policiesObj = root.get("policies");
            if (!(policiesObj instanceof List)) {
                if (logger != null) logger.log("WARN", "Invalid policies.yaml: 'policies' is missing or not a list.");
                return config;
            }
            for (Object item : (List<?>) policiesObj) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> raw = (Map<String, Object>) item;
                Object idObj = raw.get("id");
                String id = idObj == null ? null : String.valueOf(idObj);
                if (id == null || id.isBlank()) {
                    continue;
                }
                TokenPolicy policy = new TokenPolicy();
                policy.id = id;
                // ignore legacy `max_skew` and `default_expiry` fields; only `permissions` are authoritative
                Object permissions = raw.get("permissions");
                if (permissions instanceof List) {
                    for (Object p : (List<?>) permissions) {
                        if (p != null) policy.permissions.add(String.valueOf(p));
                    }
                }
                config.policies.put(id, policy);
            }
            if (logger != null) logger.log("INFO", "Loaded token policies from policies.yaml");
        } catch (Exception e) {
            if (logger != null) {
                logger.log("ERROR", "Failed to load policies.yaml: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return defaults();
        }
        return config;
    }

    public static TokensConfig defaults() {
        TokensConfig config = new TokensConfig();
        TokenPolicy moderator = new TokenPolicy();
        moderator.id = "moderator";
        moderator.permissions.add("ping");
        moderator.permissions.add("server.log");
        moderator.permissions.add("server.stream");
        moderator.permissions.add("server.run");
        moderator.permissions.add("world.*");
        moderator.permissions.add("system.*");
        config.policies.put("moderator", moderator);
        return config;
    }

    private TokensConfig() {}
}
