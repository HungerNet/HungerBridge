package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.http.ErrorResponse;
import com.hungerbridge.common.http.Response;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public final class AdminHandler implements HttpHandler {

    private final AdminService admin;
    private final Config config;
    private final String action;

    public AdminHandler(AdminService admin, Config config, String action) {
        this.admin = admin;
        this.config = config;
        this.action = action;
    }

    private boolean requireAdmin(HttpExchange ex) throws IOException {
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthenticated", "authentication required", config);
            return false;
        }
        Boolean legacy = (Boolean) ex.getAttribute("hb.auth.legacy");
        if (legacy != null && legacy) return true;
        Object tok = ex.getAttribute("hb.auth.token");
        if (tok instanceof com.hungerbridge.common.security.TokenManager.Token) {
            com.hungerbridge.common.security.TokenManager.Token t = (com.hungerbridge.common.security.TokenManager.Token) tok;
            if (t.whitelist != null && t.whitelist.contains("admin")) return true;
        }
        HttpUtil.error(ex, 403, "forbidden", "admin rights required", config);
        return false;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!requireAdmin(ex)) return;

        try {
            switch (action) {
                case "tokens_list": {
                    Map<String, com.hungerbridge.common.security.TokenManager.Token> toks = admin.listTokens();
                    JsonArray arr = new JsonArray();
                    for (Map.Entry<String, com.hungerbridge.common.security.TokenManager.Token> e : toks.entrySet()) {
                        com.hungerbridge.common.security.TokenManager.Token t = e.getValue();
                        JsonObject o = new JsonObject();
                        o.addProperty("id", t.id);
                        o.addProperty("revoked", t.revoked);
                        o.addProperty("expiry", t.expiry);
                        o.add("whitelist", com.hungerbridge.common.Json.GSON.toJsonTree(t.whitelist));
                        o.add("blacklist", com.hungerbridge.common.Json.GSON.toJsonTree(t.blacklist));
                        arr.add(o);
                    }
                    HttpUtil.writeJson(ex, 200, Response.ok(arr));
                    break;
                }
                case "tokens_create": {
                    JsonObject body = HttpUtil.readJson(ex);
                    long ttl = body != null && body.has("ttl") ? body.get("ttl").getAsLong() : config.getCommandsConfig().tokenDefaultTtl;
                    List<String> wl = body != null && body.has("whitelist") ? com.hungerbridge.common.Json.GSON.fromJson(body.get("whitelist"), List.class) : config.getCommandsConfig().tokenDefaultWhitelist;
                    List<String> bl = body != null && body.has("blacklist") ? com.hungerbridge.common.Json.GSON.fromJson(body.get("blacklist"), List.class) : config.getCommandsConfig().tokenDefaultBlacklist;
                    com.hungerbridge.common.security.TokenManager.Token t = admin.createToken(ttl, wl, bl);
                    if (t == null) { HttpUtil.error(ex, 500, "create_failed", "failed to create token", config); break; }
                    JsonObject out = new JsonObject();
                    out.addProperty("id", t.id);
                    out.addProperty("secret", t.secret);
                    HttpUtil.writeJson(ex, 200, Response.ok(out));
                    break;
                }
                case "tokens_revoke": {
                    JsonObject body = HttpUtil.readJson(ex);
                    if (body == null || !body.has("id")) { HttpUtil.error(ex, 400, "missing_id", "token id required", config); break; }
                    String id = body.get("id").getAsString();
                    boolean ok = admin.revokeToken(id);
                    if (!ok) { HttpUtil.error(ex, 404, "not_found", "token not found", config); break; }
                    HttpUtil.writeJson(ex, 200, Response.ok());
                    break;
                }
                case "tokens_rotate": {
                    JsonObject body = HttpUtil.readJson(ex);
                    if (body == null || !body.has("id")) { HttpUtil.error(ex, 400, "missing_id", "token id required", config); break; }
                    String id = body.get("id").getAsString();
                    com.hungerbridge.common.security.TokenManager.Token t = admin.rotateToken(id);
                    if (t == null) { HttpUtil.error(ex, 404, "not_found", "token not found or revoked", config); break; }
                    JsonObject out = new JsonObject(); out.addProperty("id", t.id); out.addProperty("secret", t.secret);
                    HttpUtil.writeJson(ex, 200, Response.ok(out));
                    break;
                }
                case "status": {
                    Map<String, Object> st = admin.getStatus();
                    HttpUtil.writeJson(ex, 200, Response.ok(st));
                    break;
                }
                case "probe": {
                    Map<String, Object> p = admin.runProbe();
                    HttpUtil.writeJson(ex, 200, Response.ok(p));
                    break;
                }
                case "ip": {
                    Map<String, Object> ip = admin.getIpStatus();
                    HttpUtil.writeJson(ex, 200, Response.ok(ip));
                    break;
                }
                case "audit": {
                    // optional ?n query param
                    String q = ex.getRequestURI().getQuery();
                    int n = 20;
                    if (q != null && q.startsWith("n=")) {
                        try { n = Integer.parseInt(q.substring(2)); } catch (NumberFormatException ignored) {}
                    }
                    List<String> lines = admin.getAuditSummary(n);
                    HttpUtil.writeJson(ex, 200, Response.ok(lines));
                    break;
                }
                case "reload": {
                    boolean ok = admin.reloadConfig();
                    if (!ok) { HttpUtil.error(ex, 500, "reload_failed", "failed to reload config", config); break; }
                    HttpUtil.writeJson(ex, 200, Response.ok());
                    break;
                }
                default:
                    HttpUtil.error(ex, 404, "not_found", "unknown admin action", config);
            }
        } catch (Exception e) {
            HttpUtil.error(ex, 500, "internal", e.getMessage(), config);
        }
    }
}
