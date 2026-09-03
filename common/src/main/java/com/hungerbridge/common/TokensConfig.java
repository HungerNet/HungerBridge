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
    public int maxSkewSeconds = 300;
    public long defaultExpirySeconds = 0L;
    public java.util.List<String> defaultEndpoints = java.util.List.of();
    public java.util.List<String> defaultCommands = java.util.List.of();
    public final Map<String, TokenPolicy> policies = new LinkedHashMap<>();

    public static final class TokenPolicy {
        public String id = "admin";
        public int maxSkewSeconds = 300;
        public long defaultExpirySeconds = 0L;
        public String endpointsMode = "blacklist";
        public java.util.List<String> endpoints = java.util.List.of();
        public String commandsMode = "blacklist";
        public java.util.List<String> commands = java.util.List.of();

        public java.util.List<String> effectiveAcl() {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            if (endpoints != null) all.addAll(endpoints);
            if (commands != null) all.addAll(commands);
            return java.util.List.copyOf(all);
        }
    }

    public TokenPolicy getPolicy(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) return policies.get("admin");
        return policies.get(tokenId);
    }

    public boolean hasPolicy(String tokenId) {
        return tokenId != null && !tokenId.isBlank() && policies.containsKey(tokenId);
    }

    @SuppressWarnings("unchecked")
    public static TokensConfig load(Path configDir) {
        TokensConfig tc = defaults();
        try {
            Path f = configDir.resolve("tokens.yaml");
            if (!Files.exists(f)) return tc;
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(f)) {
                Object obj = yaml.load(in);
                if (!(obj instanceof Map)) return tc;
                Map<String, Object> root = (Map<String, Object>) obj;
                Object tokensObj = root.get("tokens");
                if (!(tokensObj instanceof java.util.List)) return tc;
                java.util.List<Object> tokens = (java.util.List<Object>) tokensObj;
                for (Object item : tokens) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> tm = (Map<String, Object>) item;
                    Object idObj = tm.get("id");
                    String id = idObj == null ? "admin" : String.valueOf(idObj);
                    if (id == null || id.isBlank()) continue;

                    TokenPolicy p = new TokenPolicy();
                    p.id = id;

                    Object skew = tm.get("max_skew");
                    if (skew instanceof Number) p.maxSkewSeconds = ((Number) skew).intValue();
                    else if (tm.get("maxSkew") instanceof Number) p.maxSkewSeconds = ((Number) tm.get("maxSkew")).intValue();
                    Object expiry = tm.get("default_expiry");
                    if (expiry instanceof Number) p.defaultExpirySeconds = ((Number) expiry).longValue();
                    else if (tm.get("defaultExpiry") instanceof Number) p.defaultExpirySeconds = ((Number) tm.get("defaultExpiry")).longValue();

                    Object endpointsMode = tm.get("endpoints_mode");
                    if (endpointsMode != null) p.endpointsMode = String.valueOf(endpointsMode).toLowerCase();
                    else if (tm.get("endpointsMode") != null) p.endpointsMode = String.valueOf(tm.get("endpointsMode")).toLowerCase();
                    Object commandsMode = tm.get("commands_mode");
                    if (commandsMode != null) p.commandsMode = String.valueOf(commandsMode).toLowerCase();
                    else if (tm.get("commandsMode") != null) p.commandsMode = String.valueOf(tm.get("commandsMode")).toLowerCase();

                    Object endpoints = tm.get("endpoints");
                    if (endpoints instanceof java.util.List) {
                        List<String> values = new ArrayList<>();
                        for (Object o : (java.util.List<Object>) endpoints) if (o != null) values.add(o.toString());
                        p.endpoints = java.util.List.copyOf(values);
                    }
                    Object commands = tm.get("commands");
                    if (commands instanceof java.util.List) {
                        List<String> values = new ArrayList<>();
                        for (Object o : (java.util.List<Object>) commands) if (o != null) values.add(o.toString());
                        p.commands = java.util.List.copyOf(values);
                    }

                    tc.policies.put(id, p);
                    if ("admin".equals(id)) {
                        tc.maxSkewSeconds = p.maxSkewSeconds;
                        tc.defaultExpirySeconds = p.defaultExpirySeconds;
                        tc.defaultEndpoints = p.endpoints;
                        tc.defaultCommands = p.commands;
                    }
                }
            }
        } catch (Exception ignored) {}
        return tc;
    }

    public static TokensConfig defaults() {
        TokensConfig tc = new TokensConfig();
        tc.maxSkewSeconds = 300;
        tc.defaultExpirySeconds = 0L;
        tc.defaultEndpoints = java.util.List.of();
        tc.defaultCommands = java.util.List.of();

        TokenPolicy admin = new TokenPolicy();
        admin.id = "admin";
        admin.maxSkewSeconds = -1;
        admin.defaultExpirySeconds = 0L;
        admin.endpointsMode = "blacklist";
        admin.commandsMode = "blacklist";
        admin.endpoints = java.util.List.of();
        admin.commands = java.util.List.of();
        tc.policies.put("admin", admin);
        return tc;
    }

    private TokensConfig() {}
}
