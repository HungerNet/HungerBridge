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
    public int maxSkewSeconds = -1;
    public long defaultExpirySeconds = 0L;
    public final Map<String, TokenPolicy> policies = new LinkedHashMap<>();

    public static final class TokenPolicy {
        public String id = "admin";
        public int maxSkewSeconds = -1;
        public long defaultExpirySeconds = 0L;
        public final List<String> permissions = new ArrayList<>();
    }

    public TokenPolicy getPolicy(String policyId) {
        if (policyId == null || policyId.isBlank()) return policies.get("admin");
        return policies.get(policyId);
    }

    public boolean hasPolicy(String policyId) {
        return policyId != null && !policyId.isBlank() && policies.containsKey(policyId);
    }

    @SuppressWarnings("unchecked")
    public static TokensConfig load(Path configDir) {
        TokensConfig config = defaults();
        Path policiesFile = configDir.resolve("policies.yaml");
        if (!Files.exists(policiesFile)) {
            return config;
        }
        try (InputStream in = Files.newInputStream(policiesFile)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                return config;
            }
            Map<String, Object> root = (Map<String, Object>) loaded;
            Object policiesObj = root.get("policies");
            if (!(policiesObj instanceof List)) {
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
                Object maxSkew = raw.get("max_skew");
                if (maxSkew instanceof Number) {
                    policy.maxSkewSeconds = ((Number) maxSkew).intValue();
                }
                Object expiry = raw.get("default_expiry");
                if (expiry instanceof Number) {
                    policy.defaultExpirySeconds = ((Number) expiry).longValue();
                }
                Object permissions = raw.get("permissions");
                if (permissions instanceof List) {
                    for (Object p : (List<?>) permissions) {
                        if (p != null) policy.permissions.add(String.valueOf(p));
                    }
                }
                config.policies.put(id, policy);
                if ("admin".equals(id)) {
                    config.maxSkewSeconds = policy.maxSkewSeconds;
                    config.defaultExpirySeconds = policy.defaultExpirySeconds;
                }
            }
        } catch (Exception ignored) {
            return defaults();
        }
        return config;
    }

    public static TokensConfig defaults() {
        TokensConfig config = new TokensConfig();
        TokenPolicy admin = new TokenPolicy();
        admin.id = "admin";
        admin.maxSkewSeconds = -1;
        admin.defaultExpirySeconds = 0L;
        admin.permissions.add("*");
        TokenPolicy moderator = new TokenPolicy();
        moderator.id = "moderator";
        moderator.maxSkewSeconds = 300;
        moderator.defaultExpirySeconds = 0L;
        moderator.permissions.add("ping");
        moderator.permissions.add("server.log");
        moderator.permissions.add("server.stream");
        moderator.permissions.add("server.run");
        moderator.permissions.add("world.*");
        moderator.permissions.add("system.*");
        moderator.permissions.add("admin.audit");
        config.policies.put("admin", admin);
        config.policies.put("moderator", moderator);
        config.maxSkewSeconds = admin.maxSkewSeconds;
        config.defaultExpirySeconds = admin.defaultExpirySeconds;
        return config;
    }

    private TokensConfig() {}
}
