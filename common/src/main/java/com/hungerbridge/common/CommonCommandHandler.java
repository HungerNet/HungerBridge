package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CommonCommandHandler {

    public static List<String> handle(BridgeServer bridgeServer, String[] args) {
        List<String> out = new ArrayList<>();
        // Admin CLI commands removed (HTTP /admin/* endpoints have been removed).

        if (args == null || args.length == 0) {
            out.add(CommandMessages.HEADER);
            out.add("Use '/hungerbridge help' for more details or '/hungerbridge token' for the token subcommands.");
            return out;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            out.addAll(CommandMessages.helpLines());
            return out;
        }

        try {
            switch (args[0].toLowerCase()) {
                case "status": {
                    com.hungerbridge.common.Config cfg = bridgeServer != null ? bridgeServer.getConfig() : null;
                    com.hungerbridge.common.security.TokenManager tm = cfg != null ? cfg.getTokenManager() : null;
                    com.hungerbridge.common.security.RateLimiter rl = cfg != null ? cfg.getRateLimiter() : null;
                    com.hungerbridge.common.TokensConfig tc = cfg != null ? cfg.getTokensConfig() : null;
                    java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
                    root.put("port", cfg != null ? cfg.getPort() : "unknown");
                    root.put("version", cfg != null ? cfg.getVersion() : "unknown");
                    root.put("tokens_count", tm != null ? tm.listTokens().size() : 0);
                    root.put("policies_count", tc != null ? tc.policies.size() : 0);
                    if (rl != null) {
                        java.util.Map<String, Object> rates = new java.util.LinkedHashMap<>();
                        rates.put("token_rps", rl.getTokenRps());
                        rates.put("token_burst", rl.getTokenBurst());
                        rates.put("ip_rps", rl.getIpRps());
                        rates.put("ip_burst", rl.getIpBurst());
                        root.put("rate_limits", rates);
                    }
                    out.addAll(CommandMessages.formatKeyValues(root));
                    return out;
                }
                case "reload": {
                    if (bridgeServer == null) { addError(out, bridgeServer, "Server unavailable."); return out; }
                    boolean ok = bridgeServer.reloadConfig();
                    if (ok) addSuccess(out, bridgeServer, "Reloaded config from disk."); else addError(out, bridgeServer, "Reload failed.");
                    return out;
                }
                case "audit": {
                    com.hungerbridge.common.Config cfg = bridgeServer != null ? bridgeServer.getConfig() : null;
                    com.hungerbridge.common.log.AuditLogger al = cfg != null ? cfg.getAuditLogger() : null;
                    int n = 50;
                    if (args.length >= 2) {
                        try { n = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                    }
                    if (al == null) { addError(out, bridgeServer, "Audit logger not initialized."); return out; }
                    java.util.List<String> lines = al.tail(n);
                    if (lines.isEmpty()) { out.add("No audit entries."); return out; }
                    out.addAll(lines);
                    return out;
                }
                case "config": {
                    com.hungerbridge.common.Config cfg = bridgeServer != null ? bridgeServer.getConfig() : null;
                    if (cfg == null) { addError(out, bridgeServer, "Server unavailable."); return out; }
                    java.util.Map<String, Object> main = new java.util.LinkedHashMap<>();
                    main.put("port", cfg.getPort());
                    main.put("players_max_list", cfg.getPlayersMaxList());
                    main.put("platform", cfg.getPlatform());
                    main.put("version", cfg.getVersion());
                    out.add("**Main**:");
                    out.addAll(CommandMessages.formatKeyValues(main));
                    out.add("**Security**:");
                    com.hungerbridge.common.security.SecurityConfig sc = cfg.getSecurityConfig();
                    if (sc != null) {
                        java.util.Map<String, Object> s = new java.util.LinkedHashMap<>();
                        s.put("ip_list_mode", sc.ipListMode);
                        s.put("ip_list_size", sc.ipList.size());
                        s.put("token_rps", sc.tokenRps);
                        s.put("token_burst", sc.tokenBurst);
                        s.put("audit_retention_days", sc.auditRetentionDays);
                        out.addAll(CommandMessages.formatKeyValues(s));
                    } else {
                        out.add("(none)");
                    }
                    out.add("**Tokens**:");
                    com.hungerbridge.common.TokensConfig tc = cfg.getTokensConfig();
                    if (tc != null) {
                        java.util.Map<String, Object> t = new java.util.LinkedHashMap<>();
                        t.put("policies_count", tc.policies.size());
                        t.put("default_expiry", tc.defaultExpirySeconds);
                        out.addAll(CommandMessages.formatKeyValues(t));
                    } else {
                        out.add("(none)");
                    }
                    return out;
                }
                case "token":
                case "tokens": {
                    // token subcommands: list, create <tokenId> <policyId> [expiry], revoke <id>, rotate <id>
                    com.hungerbridge.common.Config cfg = bridgeServer != null ? bridgeServer.getConfig() : null;
                    com.hungerbridge.common.security.TokenManager tm = cfg != null ? cfg.getTokenManager() : null;
                    if (args.length == 1) {
                        out.add(CommandMessages.TOKENS_SUB);
                        return out;
                    }
                    String sub = args[1].toLowerCase();
                    switch (sub) {
                        case "list": {
                            if (tm == null) { addError(out, bridgeServer, "Token manager not initialized."); return out; }
                            Map<String, com.hungerbridge.common.security.TokenManager.Token> map = tm.listTokens();
                            java.util.List<String[]> rows = new java.util.ArrayList<>();
                            for (var t : map.values()) {
                                rows.add(new String[]{t.id, String.valueOf(t.revoked), String.valueOf(t.expiry), String.valueOf(t.maxSkew)});
                            }
                            out.addAll(CommandMessages.formatTable(rows, new String[]{"id","revoked","expiry","max_skew"}));
                            return out;
                        }
                        case "create": {
                            if (tm == null || cfg == null) { addError(out, bridgeServer, "Token manager not initialized."); return out; }
                            if (args.length < 3) { addError(out, bridgeServer, "Usage: token create <tokenId> <policyId> [expiry]"); return out; }
                            // Per policy: ignore provided tokenId and generate a server-side id
                            String providedPolicyOrId = args[2];
                            String policyId = providedPolicyOrId;
                            long expiry = 0L;
                            if (args.length >= 4) {
                                try { expiry = Long.parseLong(args[3]); } catch (NumberFormatException nfe) { addError(out, bridgeServer, "Invalid expiry value."); return out; }
                            }
                            com.hungerbridge.common.TokensConfig tc = cfg.getTokensConfig();
                            if (tc != null && policyId != null && !policyId.isBlank() && !tc.hasPolicy(policyId)) { addError(out, bridgeServer, "Unknown policy id: " + policyId); return out; }
                            // create token without specifying id so TokenManager generates one
                            TokenManager.IssueResult res = tm.issueTokenWithPickup(null, expiry, null, 300);
                            if (res == null) { addError(out, bridgeServer, "Failed to create token."); return out; }
                            TokenManager.PickupRecord pr = tm.consumePickup(res.pickupId);
                            if (pr == null) { addError(out, bridgeServer, "Failed to retrieve token secret."); return out; }
                            out.add(CommandMessages.createdToken(res.tokenId, pr.secret));
                            return out;
                        }
                        case "revoke": {
                            if (tm == null) { addError(out, bridgeServer, "Token manager not initialized."); return out; }
                            if (args.length < 3) { addError(out, bridgeServer, "Usage: token revoke <id>"); return out; }
                            String id = args[2];
                            boolean ok = tm.revokeToken(id);
                            if (!ok) { addError(out, bridgeServer, "Token not found: " + id); return out; }
                            addSuccess(out, bridgeServer, "Revoked token: " + id);
                            return out;
                        }
                        case "rotate": {
                            if (tm == null) { addError(out, bridgeServer, "Token manager not initialized."); return out; }
                            if (args.length < 3) { addError(out, bridgeServer, "Usage: token rotate <id>"); return out; }
                            String id = args[2];
                            TokenManager.IssueResult ir = tm.rotateTokenWithPickup(id, 300);
                            if (ir == null) { addError(out, bridgeServer, "Failed to rotate token: " + id); return out; }
                            TokenManager.PickupRecord pr = tm.consumePickup(ir.pickupId);
                            if (pr == null) { addError(out, bridgeServer, "Failed to retrieve rotated token secret."); return out; }
                            out.add(CommandMessages.rotatedToken(ir.tokenId, pr.secret));
                            return out;
                        }
                        default:
                            addError(out, bridgeServer, "Unknown token subcommand.");
                            return out;
                    }
                }
                default:
                    addError(out, bridgeServer, "Unknown subcommand.");
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "An unexpected error occurred." : e.getMessage();
            addError(out, bridgeServer, msg);
            try {
                if (bridgeServer != null) {
                    com.hungerbridge.common.Logger l = bridgeServer.getLogger();
                    if (l != null) l.log("ERROR", "Command handler exception: " + msg);
                }
            } catch (Exception ignored) {
            }
        }

        return out;
    }

    private static void addError(List<String> out, BridgeServer bridgeServer, String message) {
        try { if (bridgeServer != null && bridgeServer.getLogger() != null) bridgeServer.getLogger().log("ERROR", message); } catch (Exception ignored) {}
    }

    private static void addWarning(List<String> out, BridgeServer bridgeServer, String message) {
        try { if (bridgeServer != null && bridgeServer.getLogger() != null) bridgeServer.getLogger().log("WARN", message); } catch (Exception ignored) {}
    }

    private static void addSuccess(List<String> out, BridgeServer bridgeServer, String message) {
        out.add(CommandMessages.success(message));
    }

    private CommonCommandHandler() {}
}
