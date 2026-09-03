package com.hungerbridge.common.security;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SecurityConfig {
    public String ipListMode = "blacklist";
    public List<String> ipList = new ArrayList<>();
    public List<String> ipWhitelist = new ArrayList<>();
    public List<String> ipBlacklist = new ArrayList<>();
    // rate limit defaults
    public double tokenRps = 5.0;
    public double tokenBurst = 10.0;
    public double ipRps = 20.0;
    public double ipBurst = 40.0;
    // audit retention (days)
    public int auditRetentionDays = 14;

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

                Object pb = root.get("private_base_url");
                if (pb instanceof String) {
                    // legacy key ignored; public_base_url is intentionally unsupported
                }
                Object pb2 = root.get("public_base_url");
                if (pb2 instanceof String) {
                    // legacy key ignored; public_base_url is intentionally unsupported
                }

                Object ipListObj = root.get("ip_list");
                if (ipListObj instanceof Map) {
                    Map<String, Object> ipMap = (Map<String, Object>) ipListObj;
                    Object mode = ipMap.get("mode");
                    if (mode != null) sc.ipListMode = String.valueOf(mode).toLowerCase();
                    Object values = ipMap.get("list");
                    if (values instanceof List) {
                        for (Object o : (List<Object>) values) if (o != null) {
                            String v = o.toString();
                            sc.ipList.add(v);
                            if ("whitelist".equals(sc.ipListMode)) sc.ipWhitelist.add(v);
                            else sc.ipBlacklist.add(v);
                        }
                    }
                }

                Object wl = root.get("ip_whitelist");
                if (wl instanceof List) {
                    for (Object o : (List<Object>) wl) if (o != null) {
                        String v = o.toString();
                        sc.ipWhitelist.add(v);
                        sc.ipList.add(v);
                    }
                }
                Object bl = root.get("ip_blacklist");
                if (bl instanceof List) {
                    for (Object o : (List<Object>) bl) if (o != null) {
                        String v = o.toString();
                        sc.ipBlacklist.add(v);
                        sc.ipList.add(v);
                    }
                }
                if (sc.ipList.isEmpty() && sc.ipWhitelist.isEmpty() && sc.ipBlacklist.isEmpty()) {
                    sc.ipListMode = "blacklist";
                }
                if (sc.ipListMode == null || sc.ipListMode.isBlank()) sc.ipListMode = "blacklist";

                Object rl = root.get("rate_limits");
                if (rl instanceof Map) {
                    Map<String, Object> rlm = (Map<String, Object>) rl;
                    Object tr = rlm.get("token_rps"); if (tr instanceof Number) sc.tokenRps = ((Number) tr).doubleValue();
                    Object tb = rlm.get("token_burst"); if (tb instanceof Number) sc.tokenBurst = ((Number) tb).doubleValue();
                    Object ir = rlm.get("ip_rps"); if (ir instanceof Number) sc.ipRps = ((Number) ir).doubleValue();
                    Object ib = rlm.get("ip_burst"); if (ib instanceof Number) sc.ipBurst = ((Number) ib).doubleValue();
                }
                Object ar = root.get("audit_retention_days");
                if (ar instanceof Number) sc.auditRetentionDays = ((Number) ar).intValue();
                Object audit = root.get("audit");
                if (audit instanceof Map) {
                    Map<String, Object> adm = (Map<String, Object>) audit;
                    Object rd = adm.get("retention_days");
                    if (rd instanceof Number) sc.auditRetentionDays = ((Number) rd).intValue();
                }
            }
        } catch (Exception e) {
            // ignore and return defaults
        }
        return sc;
    }
}
