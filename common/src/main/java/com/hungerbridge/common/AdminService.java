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

    public TokenManager.Token createToken(String policyId, String tokenId, long expirySeconds, List<String> whitelist, List<String> blacklist) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return null;
        TokensConfig tc = config.getTokensConfig();
        if (tc != null && policyId != null && !policyId.isBlank() && !tc.hasPolicy(policyId)) {
            return null;
        }
        List<String> effectiveWhitelist = whitelist == null ? new ArrayList<>() : new ArrayList<>(whitelist);
        List<String> effectiveBlacklist = blacklist == null ? new ArrayList<>() : new ArrayList<>(blacklist);
        // (name removed — no uniqueness checks)
        if (tc != null && (effectiveWhitelist.isEmpty() && effectiveBlacklist.isEmpty())) {
            TokensConfig.TokenPolicy policy = tc.getPolicy(policyId);
            if (policy != null) {
                java.util.LinkedHashSet<String> resolved = new java.util.LinkedHashSet<>();
                if (policy.endpoints != null) resolved.addAll(policy.endpoints);
                if (policy.commands != null) resolved.addAll(policy.commands);
                if (!resolved.isEmpty()) {
                    if ("whitelist".equalsIgnoreCase(policy.endpointsMode) || "whitelist".equalsIgnoreCase(policy.commandsMode)) {
                        effectiveWhitelist.addAll(resolved);
                    } else {
                        effectiveBlacklist.addAll(resolved);
                    }
                }
                if (expirySeconds <= 0 && policy.defaultExpirySeconds > 0) expirySeconds = policy.defaultExpirySeconds;
            }
        }
        // Create a runtime token with the requested tokenId (if provided) or generated id.
        TokenManager.Token t = tm.createToken(tokenId, expirySeconds, effectiveWhitelist.isEmpty() ? null : effectiveWhitelist, effectiveBlacklist.isEmpty() ? null : effectiveBlacklist);
        if (t != null) {
            if (policyId != null && !policyId.isBlank()) tm.setTokenPolicyId(t.id, policyId);
        }
        return t;
    }

    /**
     * Create a token and issue a one-time pickup record. Returns the IssueResult containing pickup and token id.
     */
    public TokenManager.IssueResult createTokenWithPickup(String policyId, String tokenId, long expirySeconds, List<String> whitelist, List<String> blacklist, int pickupTtlSeconds) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return null;
        TokensConfig tc = config.getTokensConfig();
        if (tc != null && policyId != null && !policyId.isBlank() && !tc.hasPolicy(policyId)) {
            return null;
        }

        List<String> effectiveWhitelist = whitelist == null ? new ArrayList<>() : new ArrayList<>(whitelist);
        List<String> effectiveBlacklist = blacklist == null ? new ArrayList<>() : new ArrayList<>(blacklist);
        // (name removed — no uniqueness checks)
        if (tc != null && (effectiveWhitelist.isEmpty() && effectiveBlacklist.isEmpty())) {
            TokensConfig.TokenPolicy policy = tc.getPolicy(policyId);
            if (policy != null) {
                java.util.LinkedHashSet<String> resolved = new java.util.LinkedHashSet<>();
                if (policy.endpoints != null) resolved.addAll(policy.endpoints);
                if (policy.commands != null) resolved.addAll(policy.commands);
                if (!resolved.isEmpty()) {
                    if ("whitelist".equalsIgnoreCase(policy.endpointsMode) || "whitelist".equalsIgnoreCase(policy.commandsMode)) {
                        effectiveWhitelist.addAll(resolved);
                    } else {
                        effectiveBlacklist.addAll(resolved);
                    }
                }
                if (expirySeconds <= 0 && policy.defaultExpirySeconds > 0) expirySeconds = policy.defaultExpirySeconds;
            }
        }

        TokenManager.IssueResult res = tm.issueTokenWithPickup(tokenId, expirySeconds, effectiveWhitelist.isEmpty() ? null : effectiveWhitelist, effectiveBlacklist.isEmpty() ? null : effectiveBlacklist, pickupTtlSeconds);
        if (res != null) {
            if (policyId != null && !policyId.isBlank()) tm.setTokenPolicyId(res.tokenId, policyId);
        }
        return res;
    }

    public TokenManager.Token createToken(long expirySeconds, List<String> whitelist, List<String> blacklist) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return null;
        return tm.createToken(null, expirySeconds, whitelist, blacklist);
    }

    public boolean revokeToken(String id) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return false;
        boolean ok = tm.revokeToken(id);
        if (ok) return true;
        if (logger != null) logger.log("WARN", "Revoke token not found: " + id);
        return false;
    }

    public TokenManager.Token rotateToken(String id) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return null;
        TokenManager.Token t = tm.rotateToken(id);
        String usedId = id;
        // log rotation
        com.hungerbridge.common.log.AuditLogger al = config.getAuditLogger();
        if (al != null && t != null) {
            Map<String, Object> extra = new HashMap<>();
            extra.put("action", "rotate");
            extra.put("token", usedId);
            al.logEvent(usedId, "local", "token.rotate", "rotated", extra);
        }
        if (t == null && logger != null) {
            logger.log("WARN", "Rotate failed for token: " + id);
        }
        return t;
    }

    public TokenManager.IssueResult rotateTokenWithPickup(String id, int pickupTtlSeconds) {
        TokenManager tm = config.getTokenManager();
        if (tm == null) return null;
        TokenManager.IssueResult res = tm.rotateTokenWithPickup(id, pickupTtlSeconds);
        if (res == null && logger != null) logger.log("WARN", "Rotate failed for token: " + id);
        return res;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        SecurityConfig sc = config.getSecurityConfig();
        if (sc != null) {
            Map<String, Object> ipList = new HashMap<>();
            ipList.put("mode", sc.ipListMode);
            ipList.put("list", sc.ipList);
            m.put("ip_list", ipList);
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
            if (logger != null) logger.log("WARN", "Failed to read audit logs: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getIpStatus() {
        Map<String, Object> m = new HashMap<>();
        SecurityConfig sc = config.getSecurityConfig();
        if (sc != null) {
            Map<String, Object> ipList = new HashMap<>();
            ipList.put("mode", sc.ipListMode);
            ipList.put("list", sc.ipList);
            m.put("ip_list", ipList);
        }
        return m;
    }

    public Map<String, Object> getConfigStatus() {
        Map<String, Object> out = new HashMap<>();
        try {
            Path cfg = configDir.resolve("config.yaml");
            Path sec = configDir.resolve("security.yaml");
            Path tokensYaml = configDir.resolve("tokens.yaml");
            Path storage = configDir.resolve("storage");
            Path tokens = storage.resolve("tokens.json");
            Path sessions = storage.resolve("sessions.json");
            Path logs = configDir.resolve("logs");

            out.put("config.yaml_exists", Files.exists(cfg));
            out.put("security.yaml_exists", Files.exists(sec));
            out.put("tokens.yaml_exists", Files.exists(tokensYaml));
            out.put("tokens_json_exists", Files.exists(tokens));
            out.put("sessions_json_exists", Files.exists(sessions));
            out.put("logs_dir_exists", Files.exists(logs));

            // validation: attempt to load
            out.put("security_valid", com.hungerbridge.common.security.SecurityConfig.load(configDir) != null);
            out.put("tokens_valid", com.hungerbridge.common.TokensConfig.load(configDir) != null);
        } catch (Exception e) {
            out.put("error", e.getMessage());
            if (logger != null) logger.log("ERROR", "Failed to compute config status: " + e.getMessage());
        }
        return out;
    }

    public boolean reloadConfig() {
        try {
            SecurityConfig sc = SecurityConfig.load(configDir);
            config.setSecurityConfig(sc);
            TokensConfig tc = TokensConfig.load(configDir);
            config.setTokensConfig(tc);
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
            if (logger != null) logger.log("ERROR", "Failed to reload config: " + e.getMessage());
            return false;
        }
    }
}
