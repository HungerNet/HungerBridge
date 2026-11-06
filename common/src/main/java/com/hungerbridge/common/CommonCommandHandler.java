package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CommonCommandHandler {

    public static List<String> handle(BridgeServer bridgeServer, String[] args) {
        return handle(bridgeServer, null, args);
    }

    public static List<String> handle(BridgeServer bridgeServer, Object source, String[] args) {
        List<String> out = new ArrayList<>();
        // Admin CLI commands removed (HTTP /admin/* endpoints have been removed).

        if (args == null || args.length == 0) {
            out.add("Usage: /hungerbridge <token|reload>");
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
                        out.add("Usage: /hungerbridge token <list|create|rotate|revoke|remove>");
                        return out;
                    }
                    String sub = args[1].toLowerCase();
                    switch (sub) {
                        case "list": {
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            Map<String, TokenManager.Token> map = tm.listTokens();
                            if (map.isEmpty()) { out.add("No tokens."); return out; }
                            for (TokenManager.Token t : map.values()) {
                                out.add(t.id + " (policy=" + t.policyId + ", revoked=" + t.revoked + ", expiry=" + (t.expiry > 0 ? t.expiry : "none") + ")");
                            }
                            return out;
                        }
                        case "create": {
                            if (args.length < 4) { out.add("Usage: /hungerbridge token create <id> <policyId> [expiry]"); return out; }
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            String tokenId = args[2];
                            String policyId = args[3];
                            com.hungerbridge.common.TokensConfig tc = cfg != null ? cfg.getTokensConfig() : null;
                            if (tc != null && !tc.hasPolicy(policyId)) { addError(out, bridgeServer, "Unknown policy id: " + policyId); return out; }

                            long expirySeconds = 0L;
                            if (args.length >= 5) {
                                try {
                                    expirySeconds = Long.parseLong(args[4]);
                                } catch (NumberFormatException e) {
                                    out.add("Invalid expiry value: " + args[4]);
                                    return out;
                                }
                            } else if (tc != null && tc.getPolicy(policyId) != null && tc.getPolicy(policyId).defaultExpiry > 0L) {
                                expirySeconds = tc.getPolicy(policyId).defaultExpiry;
                            }

                            TokenManager.IssueResult res = tm.issueTokenWithPickup(tokenId, expirySeconds, null, 300);
                            if (res == null) { addError(out, bridgeServer, "Token id already exists or is pending pickup: " + tokenId); return out; }
                            tm.setTokenPolicyId(res.tokenId, policyId);
                            if (expirySeconds > 0L) {
                                TokenManager.Token token = tm.listTokens().get(res.tokenId);
                                if (token != null) {
                                    addSuccess(out, bridgeServer, "Token created with expiry: " + token.expiry + " (unix seconds)");
                                }
                            }
                            addSuccess(out, bridgeServer, "Pickup passkey: " + res.passkey);
                            addSuccess(out, bridgeServer, "Token created. Retrieve it at: /pickup/" + res.pickupId + "?passkey=" + res.passkey);
                            return out;
                        }
                        case "rotate": {
                            if (args.length < 3) { out.add("Usage: /hungerbridge token rotate <id>"); return out; }
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            // Rotation changes the secret, so it must come with a new pickup;
                            // otherwise nobody can ever obtain the new secret.
                            TokenManager.IssueResult rotated = tm.rotateTokenWithPickup(args[2], 300);
                            if (rotated == null) { addError(out, bridgeServer, "Token not found or revoked: " + args[2]); return out; }
                            addSuccess(out, bridgeServer, "Rotated token: " + args[2] + " (the old secret no longer works)");
                            addSuccess(out, bridgeServer, "Pickup passkey: " + rotated.passkey);
                            addSuccess(out, bridgeServer, "Retrieve the new secret at: /pickup/" + rotated.pickupId + "?passkey=" + rotated.passkey);
                            return out;
                        }
                        case "revoke": {
                            if (args.length < 3) { out.add("Usage: /hungerbridge token revoke <id>"); return out; }
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            boolean ok = tm.revokeToken(args[2]);
                            if (!ok) { addError(out, bridgeServer, "Token not found: " + args[2]); return out; }
                            addSuccess(out, bridgeServer, "Revoked token: " + args[2]);
                            return out;
                        }
                        case "remove": {
                            if (args.length < 3) { out.add("Usage: /hungerbridge token remove <id>"); return out; }
                            if (tm == null) { addError(out, bridgeServer, "Token manager unavailable."); return out; }
                            boolean ok = tm.removeToken(args[2]);
                            if (!ok) { addError(out, bridgeServer, "Token not found: " + args[2]); return out; }
                            addSuccess(out, bridgeServer, "Removed token: " + args[2]);
                            return out;
                        }
                        default:
                            out.add("Unknown token subcommand.");
                            out.add("Usage: /hungerbridge token <list|create|rotate|revoke|remove>");
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

    // Errors are returned to the command sender (previously they were only logged, so a
    // failed "token create" printed nothing at all).
    private static void addError(List<String> out, BridgeServer bridgeServer, String message) {
        out.add(CommandMessages.error("Error: " + message));
    }

    private static void addSuccess(List<String> out, BridgeServer bridgeServer, String message) {
        out.add(CommandMessages.success(message));
    }

    private CommonCommandHandler() {}
}
