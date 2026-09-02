package com.hungerbridge.common;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class TokensConfig {
    public int allowedSkewSeconds = 300;
    public long defaultTokenTtlSeconds = 0;

    @SuppressWarnings("unchecked")
    public static TokensConfig load(Path configDir) {
        TokensConfig tc = new TokensConfig();
        try {
            Path f = configDir.resolve("tokens.yaml");
            if (!Files.exists(f)) return tc;
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(f)) {
                Object obj = yaml.load(in);
                if (!(obj instanceof Map)) return tc;
                Map<String, Object> root = (Map<String, Object>) obj;
                Object as = root.get("allowed_skew_seconds");
                if (as instanceof Number) tc.allowedSkewSeconds = ((Number) as).intValue();
                Object dt = root.get("default_token_ttl_seconds");
                if (dt instanceof Number) tc.defaultTokenTtlSeconds = ((Number) dt).longValue();
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
