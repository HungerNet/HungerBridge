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
                            // Tokens are disabled; no-op list.
                            out.add("[]");
                            return out;
                        }
                        case "create": {
                            // Tokens disabled: acknowledge creation request but do not create tokens.
                            if (args.length < 4) { addError(out, bridgeServer, "Usage: token create <tokenId> <policyId> [expiry]"); return out; }
                            String tokenId = args[2];
                            String policyId = args[3];
                            long expiry = 0L;
                            com.hungerbridge.common.TokensConfig tc = cfg != null ? cfg.getTokensConfig() : null;
                            if (tc != null && !tc.hasPolicy(policyId)) { addError(out, bridgeServer, "Unknown policy id: " + policyId); return out; }
                            if (args.length >= 5) {
                                try { expiry = Long.parseLong(args[4]); } catch (NumberFormatException nfe) { addError(out, bridgeServer, "Invalid expiry value."); return out; }
                            } else {
                                if (tc != null) {
                                    var p = tc.getPolicy(policyId);
                                    if (p != null) expiry = p.defaultExpirySeconds;
                                }
                            }
                            addSuccess(out, bridgeServer, "Created token: " + tokenId + " (no-op)");
                            return out;
                        }
                        case "revoke": {
                            // No-op revoke.
                            if (args.length < 3) { addError(out, bridgeServer, "Usage: token revoke <id>"); return out; }
                            addSuccess(out, bridgeServer, "Revoked token: " + args[2] + " (no-op)");
                            return out;
                        }
                        case "rotate": {
                            // No-op rotate.
                            if (args.length < 3) { addError(out, bridgeServer, "Usage: token rotate <id>"); return out; }
                            addSuccess(out, bridgeServer, "Rotated token: " + args[2] + " (no-op)");
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
