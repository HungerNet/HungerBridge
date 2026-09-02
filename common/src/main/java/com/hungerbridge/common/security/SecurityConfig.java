package com.hungerbridge.common.security;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SecurityConfig {
    public boolean selfProbe = false;
    public String publicBaseUrl = null;
    public int probeTimeoutMs = 2000;
    public List<String> ipWhitelist = new ArrayList<>();
    public List<String> ipBlacklist = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public static SecurityConfig load(Path configDir) {
        SecurityConfig sc = new SecurityConfig();
        try {
            Path f = configDir.resolve("security.yaml");
            if (!Files.exists(f)) return sc;
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(f)) {
                Object obj = yaml.load(in);
                if (!(obj instanceof Map)) return sc;
                Map<String, Object> root = (Map<String, Object>) obj;
                Object sp = root.get("self_probe");
                if (sp instanceof Boolean) sc.selfProbe = (Boolean) sp;
                Object pb = root.get("public_base_url");
                if (pb instanceof String) sc.publicBaseUrl = (String) pb;
                Object pt = root.get("probe_timeout_ms");
                if (pt instanceof Number) sc.probeTimeoutMs = ((Number) pt).intValue();
                Object wl = root.get("ip_whitelist");
                if (wl instanceof List) {
                    for (Object o : (List<Object>) wl) if (o != null) sc.ipWhitelist.add(o.toString());
                }
                Object bl = root.get("ip_blacklist");
                if (bl instanceof List) {
                    for (Object o : (List<Object>) bl) if (o != null) sc.ipBlacklist.add(o.toString());
                }
            }
        } catch (Exception e) {
            // ignore and return defaults
        }
        return sc;
    }
}
