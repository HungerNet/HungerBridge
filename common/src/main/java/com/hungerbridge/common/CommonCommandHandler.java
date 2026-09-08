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
                            if (args.length < 4) { addError(out, bridgeServer, "Usage: token create <tokenId> <policyId> [expiry]"); return out; }
                            String tokenId = args[2];
                            String policyId = args[3];
                            long expiry = 0L;
                            com.hungerbridge.common.TokensConfig tc = cfg.getTokensConfig();
                            if (tc != null && !tc.hasPolicy(policyId)) { addError(out, bridgeServer, "Unknown policy id: " + policyId); return out; }
                            if (args.length >= 5) {
                                try { expiry = Long.parseLong(args[4]); } catch (NumberFormatException nfe) { addError(out, bridgeServer, "Invalid expiry value."); return out; }
                            } else {
                                // if omitted, use policy default if available
                                if (tc != null) {
                                    var p = tc.getPolicy(policyId);
                                    if (p != null) expiry = p.defaultExpirySeconds;
                                }
                            }
                            TokenManager.IssueResult res = tm.issueTokenWithPickup(tokenId, expiry, null, 300);
                            if (res == null) { addError(out, bridgeServer, "Failed to create token."); return out; }
                            TokenManager.PickupRecord pr = tm.consumePickup(res.pickupId);
                            if (pr == null) { addError(out, bridgeServer, "Failed to retrieve token secret."); return out; }
                            com.google.gson.JsonObject resp = com.hungerbridge.common.Json.obj(
                                "ok", true,
                                "id", res.tokenId,
                                "secret", pr.secret,
                                "expiry", expiry
                            );
                            out.add(com.hungerbridge.common.Json.stringify(resp));
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
