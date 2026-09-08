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
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            Map<String, TokenManager.Token> map = tm.listTokens();
                            if (map.isEmpty()) { out.add("No tokens."); return out; }
                            for (TokenManager.Token t : map.values()) {
                                out.add(t.id + " (policy=" + t.policyId + ", revoked=" + t.revoked + ")");
                            }
                            return out;
                        }
                        case "create": {
                            if (args.length < 4) { addError(out, bridgeServer, "Usage: token create <tokenId> <policyId>"); return out; }
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            String tokenId = args[2];
                            String policyId = args[3];
                            com.hungerbridge.common.TokensConfig tc = cfg != null ? cfg.getTokensConfig() : null;
                            if (tc != null && !tc.hasPolicy(policyId)) { addError(out, bridgeServer, "Unknown policy id: " + policyId); return out; }

                            TokenManager.IssueResult res = tm.issueTokenWithPickup(tokenId, null, 300);
                            if (res == null) { addError(out, bridgeServer, "Failed to create token."); return out; }
                            // bind policy
                            tm.setTokenPolicyId(res.tokenId, policyId);
                            // Do NOT print secret. Provide pickup URL.
                            addSuccess(out, bridgeServer, "Token created. Retrieve it at: /pickup/" + res.pickupId);
                            return out;
                        }
                        case "revoke": {
                            if (args.length < 3) { addError(out, bridgeServer, "Usage: token revoke <id>"); return out; }
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            boolean ok = tm.revokeToken(args[2]);
                            if (!ok) { addError(out, bridgeServer, "Token not found: " + args[2]); return out; }
                            addSuccess(out, bridgeServer, "Revoked token: " + args[2]);
                            return out;
                        }
                        case "remove": {
                            if (args.length < 3) { addError(out, bridgeServer, "Usage: token remove <id>"); return out; }
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            boolean ok = tm.removeToken(args[2]);
                            if (!ok) { addError(out, bridgeServer, "Token not found: " + args[2]); return out; }
                            addSuccess(out, bridgeServer, "Removed token: " + args[2]);
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
