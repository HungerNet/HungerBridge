package com.hungerbridge.common;

import com.hungerbridge.common.log.AuditLogger;
import com.hungerbridge.common.security.TokenManager;
import com.hungerbridge.common.security.IpMatcher;
import com.hungerbridge.common.security.SecurityConfig;
import com.hungerbridge.common.security.RateLimiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AdminService {

    private final Path configDir;
    private final Config config;
    private final Logger logger;
    private final BridgeServer bridgeServer;

    public AdminService(Path configDir, Config config, Logger logger, BridgeServer bridgeServer) {
        this.configDir = configDir;
        this.config = config;
        this.logger = logger;
        this.bridgeServer = bridgeServer;
    }

    public Map<String, TokenManager.Token> listTokens() {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return Collections.emptyMap();
        return tm.listTokens();
    }

    public TokenManager.Token createToken(long ttlSeconds, List<String> whitelist, List<String> blacklist) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return null;
        return tm.createToken(ttlSeconds, whitelist, blacklist);
    }

    public boolean revokeToken(String id) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return false;
        return tm.revokeToken(id);
    }

    public TokenManager.Token rotateToken(String id) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return null;
        TokenManager.Token t = tm.rotateToken(id);
        // log rotation
        com.hungerbridge.common.log.AuditLogger al = config.getAuditLogger();
        if (al != null && t != null) {
            Map<String, Object> extra = new HashMap<>();
            extra.put("action", "rotate");
            extra.put("token", id);
            al.logEvent(id, "local", "token.rotate", "rotated", extra);
        }
        return t;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        SecurityConfig sc = config.getSecurityConfig();
        if (sc != null) {
            m.put("self_probe", sc.selfProbe);
            m.put("public_base_url", sc.publicBaseUrl);
            m.put("probe_timeout_ms", sc.probeTimeoutMs);
            m.put("ip_whitelist", sc.ipWhitelist);
            m.put("ip_blacklist", sc.ipBlacklist);
        }
        RateLimiter rl = config.getRateLimiter();
        if (rl != null) {
            Map<String, Object> rlmap = new HashMap<>();
            rlmap.put("token_rps", rl.getTokenRps());
            rlmap.put("token_burst", rl.getTokenBurst());
            rlmap.put("ip_rps", rl.getIpRps());
            rlmap.put("ip_burst", rl.getIpBurst());
            m.put("rate_limits", rlmap);
        }
        return m;
    }

    public Map<String, Object> runProbe() {
        Map<String, Object> out = new HashMap<>();
        SecurityConfig sc = config.getSecurityConfig();
        if (sc == null || sc.publicBaseUrl == null) {
            out.put("ok", false);
            out.put("message", "no_public_base_url");
            return out;
        }
        String probe = sc.publicBaseUrl.trim();
        if (probe.startsWith("https://")) probe = "http://" + probe.substring(8);
        if (!probe.startsWith("http://")) probe = "http://" + probe;
        if (probe.endsWith("/")) probe = probe.substring(0, probe.length()-1);
        String probeUrl = probe + "/ping";
        try {
            java.net.URL url = new java.net.URL(probeUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(sc.probeTimeoutMs);
            conn.setReadTimeout(sc.probeTimeoutMs);
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            out.put("ok", false);
            out.put("code", code);
            out.put("message", "origin_reachable");
            conn.disconnect();
        } catch (Exception e) {
            out.put("ok", true);
            out.put("message", "probe_unreachable_or_timed_out");
        }
        return out;
    }

    public List<String> getAuditSummary(int lastN) {
        try {
            Path logs = configDir.resolve("logs");
            if (!Files.exists(logs)) return Collections.emptyList();
            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> s = Files.list(logs)) {
                s.filter(p -> p.getFileName().toString().endsWith(".audit.log"))
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(files::add);
            }
            List<String> collected = new ArrayList<>();
            for (Path p : files) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) collected.addAll(lines);
                if (collected.size() >= lastN) break;
            }
            int start = Math.max(0, collected.size() - lastN);
            return new ArrayList<>(collected.subList(start, collected.size()));
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getIpStatus() {
        Map<String, Object> m = new HashMap<>();
        SecurityConfig sc = config.getSecurityConfig();
        if (sc != null) {
            m.put("ip_whitelist", sc.ipWhitelist);
            m.put("ip_blacklist", sc.ipBlacklist);
        }
        return m;
    }

    public Map<String, Object> getConfigStatus() {
        Map<String, Object> out = new HashMap<>();
        try {
            Path cfg = configDir.resolve("config.yaml");
            Path sec = configDir.resolve("security.yaml");
            Path cmd = configDir.resolve("commands.yaml");
            Path storage = configDir.resolve("storage");
            Path tokens = storage.resolve("tokens.json");
            Path sessions = storage.resolve("sessions.json");
            Path logs = configDir.resolve("logs");

            out.put("config.yaml_exists", Files.exists(cfg));
            out.put("security.yaml_exists", Files.exists(sec));
            out.put("commands.yaml_exists", Files.exists(cmd));
            out.put("tokens_json_exists", Files.exists(tokens));
            out.put("sessions_json_exists", Files.exists(sessions));
            out.put("logs_dir_exists", Files.exists(logs));

            // validation: attempt to load
            out.put("security_valid", com.hungerbridge.common.security.SecurityConfig.load(configDir) != null);
            out.put("commands_valid", com.hungerbridge.common.CommandsConfig.load(configDir) != null);
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    public boolean reloadConfig() {
        try {
            SecurityConfig sc = SecurityConfig.load(configDir);
            config.setSecurityConfig(sc);
            com.hungerbridge.common.CommandsConfig cc = com.hungerbridge.common.CommandsConfig.load(configDir);
            config.setCommandsConfig(cc);
            // reload rate-limits into rate limiter
            com.hungerbridge.common.security.RateLimiter rl = config.getRateLimiter();
            if (rl != null && sc != null) {
                rl.setLimits(sc.tokenRps, sc.tokenBurst, sc.ipRps, sc.ipBurst);
            }
            // prune audit logs according to retention
            com.hungerbridge.common.log.AuditLogger al = config.getAuditLogger();
            if (al != null && sc != null) {
                try { al.pruneOldLogs(sc.auditRetentionDays); } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
