package com.hungerbridge.common;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class TokensConfig {
    public int allowedSkewSeconds = 300;
    public long defaultTokenTtlSeconds = 0;
    public java.util.List<String> defaultWhitelist = java.util.List.of();
    public java.util.List<String> defaultBlacklist = java.util.List.of();

    @SuppressWarnings("unchecked")
    public static TokensConfig load(Path configDir) {
        TokensConfig tc = new TokensConfig();
        try {
            // Prefer token policy embedded in config.yaml under the 'tokens' key.
            Path cfg = configDir.resolve("config.yaml");
            Yaml yaml = new Yaml();
            if (Files.exists(cfg)) {
                try (InputStream in = Files.newInputStream(cfg)) {
                    Object obj = yaml.load(in);
                    if (obj instanceof Map) {
                        Map<String, Object> root = (Map<String, Object>) obj;
                        Object tokensObj = root.get("tokens");
                        if (tokensObj instanceof Map) {
                            Map<String, Object> troot = (Map<String, Object>) tokensObj;
                            Object as = troot.get("allowed_skew_seconds");
                            if (as instanceof Number) tc.allowedSkewSeconds = ((Number) as).intValue();
                            Object dt = troot.get("default_token_ttl_seconds");
                            if (dt instanceof Number) tc.defaultTokenTtlSeconds = ((Number) dt).longValue();
                            // also allow token_presets in the same config file for defaults
                            Object tp = troot.get("token_presets");
                            if (tp instanceof Map) {
                                Map<String, Object> tpm = (Map<String, Object>) tp;
                                Object skew = tpm.get("skew"); if (skew instanceof Number) tc.allowedSkewSeconds = ((Number) skew).intValue();
                                Object ttl = tpm.get("ttl"); if (ttl instanceof Number) tc.defaultTokenTtlSeconds = ((Number) ttl).longValue();
                                Object wl = tpm.get("whitelist");
                                if (wl instanceof java.util.List) {
                                    java.util.List<String> l = new java.util.ArrayList<>();
                                    for (Object o : (java.util.List<Object>) wl) if (o != null) l.add(o.toString());
                                    tc.defaultWhitelist = java.util.List.copyOf(l);
                                }
                                Object bl = tpm.get("blacklist");
                                if (bl instanceof java.util.List) {
                                    java.util.List<String> l2 = new java.util.ArrayList<>();
                                    for (Object o : (java.util.List<Object>) bl) if (o != null) l2.add(o.toString());
                                    tc.defaultBlacklist = java.util.List.copyOf(l2);
                                }
                            }
                            return tc;
                        }
                    }
                }
            }

            // Fallback: legacy tokens.yaml
            Path f = configDir.resolve("tokens.yaml");
            if (!Files.exists(f)) return tc;
            try (InputStream in = Files.newInputStream(f)) {
                Object obj = yaml.load(in);
                if (!(obj instanceof Map)) return tc;
                Map<String, Object> root = (Map<String, Object>) obj;
                Object as = root.get("allowed_skew_seconds");
                if (as instanceof Number) tc.allowedSkewSeconds = ((Number) as).intValue();
                Object dt = root.get("default_token_ttl_seconds");
                if (dt instanceof Number) tc.defaultTokenTtlSeconds = ((Number) dt).longValue();
                Object tp = root.get("token_presets");
                if (tp instanceof Map) {
                    Map<String, Object> tpm = (Map<String, Object>) tp;
                    Object skew = tpm.get("skew"); if (skew instanceof Number) tc.allowedSkewSeconds = ((Number) skew).intValue();
                    Object ttl = tpm.get("ttl"); if (ttl instanceof Number) tc.defaultTokenTtlSeconds = ((Number) ttl).longValue();
                    Object wl = tpm.get("whitelist");
                    if (wl instanceof java.util.List) {
                        java.util.List<String> l = new java.util.ArrayList<>();
                        for (Object o : (java.util.List<Object>) wl) if (o != null) l.add(o.toString());
                        tc.defaultWhitelist = java.util.List.copyOf(l);
                    }
                    Object bl = tpm.get("blacklist");
                    if (bl instanceof java.util.List) {
                        java.util.List<String> l2 = new java.util.ArrayList<>();
                        for (Object o : (java.util.List<Object>) bl) if (o != null) l2.add(o.toString());
                        tc.defaultBlacklist = java.util.List.copyOf(l2);
                    }
                }
            }
        } catch (Exception ignored) {}
        return tc;
    }

    private TokensConfig() {}

    public static TokensConfig fromValues(int allowedSkewSeconds, long defaultTokenTtlSeconds) {
        TokensConfig tc = new TokensConfig();
        tc.allowedSkewSeconds = allowedSkewSeconds;
        tc.defaultTokenTtlSeconds = defaultTokenTtlSeconds;
        return tc;
    }
}
