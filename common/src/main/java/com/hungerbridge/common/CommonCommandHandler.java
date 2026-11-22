package com.hungerbridge.common;

import com.hungerbridge.common.message.BridgeMessage;
import com.hungerbridge.common.security.TokenManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /hungerbridge} ({@code /hb}) for every loader: platforms only parse arguments and
 * render the returned {@link BridgeMessage}s. Console only (pickups print a secret).
 */
public final class CommonCommandHandler {

    /** Lifetime of the pickup printed by {@code token create} and {@code token rotate}. */
    public static final int PICKUP_TTL_SECONDS = 300;

    static final List<String> USAGE = List.of(
            "/hb help",
            "/hb reload",
            "/hb token list",
            "/hb token create <id> <policy> [expirySeconds]",
            "/hb token rotate <id>",
            "/hb token revoke <id>",
            "/hb token remove <id>");

    private CommonCommandHandler() {}

    public static List<BridgeMessage> handle(BridgeServer bridge, String[] args) {
        List<BridgeMessage> out = new ArrayList<>();
        try {
            String sub = args == null || args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "help" -> help(out);
                case "reload" -> reload(bridge, out);
                case "token" -> token(bridge, args, out);
                default -> {
                    out.add(BridgeMessage.header("Commands"));
                    out.add(BridgeMessage.error("Unknown command: " + args[0]));
                    USAGE.forEach(u -> out.add(BridgeMessage.usage(u)));
                }
            }
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            out.add(BridgeMessage.error("Command failed: " + message));
            if (bridge != null && bridge.getLogger() != null) bridge.getLogger().error("/hb command failed: " + e);
        }
        return out;
    }

    private static void help(List<BridgeMessage> out) {
        out.add(BridgeMessage.header("Commands"));
        USAGE.forEach(u -> out.add(BridgeMessage.usage(u)));
        out.add(BridgeMessage.info("/hb is short for /hungerbridge; console only"));
    }

    private static void reload(BridgeServer bridge, List<BridgeMessage> out) {
        out.add(BridgeMessage.header("Reload"));
        if (bridge == null) {
            out.add(BridgeMessage.error("The bridge is not running"));
        } else if (bridge.reloadConfig()) {
            out.add(BridgeMessage.success("Configuration reloaded from disk"));
        } else {
            out.add(BridgeMessage.error("Reload failed; see the server log"));
        }
    }

    private static void token(BridgeServer bridge, String[] args, List<BridgeMessage> out) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        TokenManager tm = bridge != null && bridge.getConfig() != null ? bridge.getConfig().getTokenManager() : null;
        switch (sub) {
            case "list" -> {
                if (!available(tm, "Tokens", out)) return;
                Map<String, TokenManager.Token> tokens = tm.listTokens();
                out.add(BridgeMessage.header("Tokens (" + tokens.size() + ")"));
                if (tokens.isEmpty()) out.add(BridgeMessage.info("No tokens. Create one with /hb token create <id> <policy>"));
                for (TokenManager.Token t : tokens.values()) {
                    out.add(BridgeMessage.detail(t.id, "policy " + t.policyId + " · " + (t.revoked ? "revoked" : "active")
                            + " · expires " + (t.expiry > 0 ? Instant.ofEpochSecond(t.expiry).toString() : "never")));
                }
            }
            case "create" -> {
                if (args.length < 4) {
                    missing("Token not created", "<id> and <policy>", USAGE.get(3), out);
                    return;
                }
                if (!available(tm, "Token created", out)) return;
                create(bridge, tm, args, out);
            }
            case "rotate", "revoke", "remove" -> {
                String title = "Token " + sub + "d";
                if (args.length < 3) {
                    missing("Token " + sub, "<id>", "/hb token " + sub + " <id>", out);
                    return;
                }
                if (!available(tm, title, out)) return;
                change(tm, sub, title, args[2], out);
            }
            default -> {
                out.add(BridgeMessage.header("Tokens"));
                if (!sub.isEmpty()) out.add(BridgeMessage.error("Unknown token command: " + args[1]));
                USAGE.subList(2, USAGE.size()).forEach(u -> out.add(BridgeMessage.usage(u)));
            }
        }
    }

    private static void create(BridgeServer bridge, TokenManager tm, String[] args, List<BridgeMessage> out) {
        String id = args[2];
        String policyId = args[3];
        TokensConfig policies = bridge.getConfig().getTokensConfig();
        out.add(BridgeMessage.header("Token created"));
        if (policies != null && !policies.hasPolicy(policyId)) {
            out.set(0, BridgeMessage.header("Token not created"));
            out.add(BridgeMessage.error("Unknown policy: " + policyId));
            out.add(BridgeMessage.info("Policies: " + String.join(", ", policies.policies.keySet())));
            return;
        }
        long expirySeconds = 0L;
        if (args.length >= 5) {
            try {
                expirySeconds = Long.parseLong(args[4]);
                if (expirySeconds < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                out.set(0, BridgeMessage.header("Token not created"));
                out.add(BridgeMessage.error("expirySeconds must be a whole number of seconds, got " + args[4]));
                return;
            }
        } else if (policies != null && policies.getPolicy(policyId).defaultExpiry > 0L) {
            expirySeconds = policies.getPolicy(policyId).defaultExpiry;
        }
        TokenManager.IssueResult issued = tm.issueTokenWithPickup(id, expirySeconds, null, PICKUP_TTL_SECONDS);
        if (issued == null) {
            out.set(0, BridgeMessage.header("Token not created"));
            out.add(BridgeMessage.error("A token with id " + id + " already exists or is waiting for pickup"));
            return;
        }
        tm.setTokenPolicyId(issued.tokenId, policyId);
        out.add(BridgeMessage.success(issued.tokenId + " uses policy " + policyId));
        TokenManager.Token token = tm.listTokens().get(issued.tokenId);
        if (token != null && token.expiry > 0) out.add(BridgeMessage.detail("Expires", Instant.ofEpochSecond(token.expiry).toString()));
        pickup(issued, out);
    }

    private static void change(TokenManager tm, String action, String title, String id, List<BridgeMessage> out) {
        out.add(BridgeMessage.header(title));
        switch (action) {
            case "rotate" -> {
                TokenManager.IssueResult rotated = tm.rotateTokenWithPickup(id, PICKUP_TTL_SECONDS);
                if (rotated == null) {
                    out.add(BridgeMessage.error("No active token with id " + id));
                    return;
                }
                out.add(BridgeMessage.success(id + " has a new secret; the old secret no longer works"));
                pickup(rotated, out);
            }
            case "revoke" -> {
                if (tm.revokeToken(id)) out.add(BridgeMessage.success(id + " can no longer authenticate"));
                else out.add(BridgeMessage.error("No token with id " + id));
            }
            default -> {
                if (tm.removeToken(id)) out.add(BridgeMessage.success(id + " was deleted"));
                else out.add(BridgeMessage.error("No token with id " + id));
            }
        }
    }

    private static void pickup(TokenManager.IssueResult issued, List<BridgeMessage> out) {
        out.add(BridgeMessage.detail("Pickup URL", "/pickup/" + issued.pickupId + "?passkey=" + issued.passkey));
        out.add(BridgeMessage.warning("The pickup URL works once and expires in " + PICKUP_TTL_SECONDS / 60 + " minutes"));
    }

    private static boolean available(TokenManager tm, String title, List<BridgeMessage> out) {
        if (tm != null) return true;
        out.add(BridgeMessage.header(title));
        out.add(BridgeMessage.error("The token manager is not available"));
        return false;
    }

    private static void missing(String title, String what, String usage, List<BridgeMessage> out) {
        out.add(BridgeMessage.header(title));
        out.add(BridgeMessage.error("Missing " + what));
        out.add(BridgeMessage.usage(usage));
    }
}
