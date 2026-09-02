package com.hungerbridge.common;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CommandsConfig {
    public boolean enableCommands = true;
    public boolean enableAdminHttp = true;
    public long tokenDefaultTtl = 3600;
    public List<String> tokenDefaultWhitelist = new ArrayList<>();
    public List<String> tokenDefaultBlacklist = new ArrayList<>();

    // token-related policy that historically lived in tokens.yaml. When merging
    // tokens.yaml into commands.yaml these fields are used as the canonical
    // policy sources (if present).
    public int allowedSkewSeconds = -1; // -1 means unset
    public long defaultTokenTtlSeconds = -1;

    public List<String> globalWhitelist = new ArrayList<>();
    public List<String> globalBlacklist = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public static CommandsConfig load(Path configDir) {
        CommandsConfig cc = new CommandsConfig();
        try {
            Path f = configDir.resolve("commands.yaml");
            if (!Files.exists(f)) return cc;
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(f)) {
                Object obj = yaml.load(in);
                if (!(obj instanceof Map)) return cc;
                Map<String, Object> root = (Map<String, Object>) obj;
                // NOTE: enable_commands is intentionally ignored to prevent disabling
                // in-game commands via configuration. Commands are always registered.
                Object eh = root.get("enable_admin_http");
                if (eh instanceof Boolean) cc.enableAdminHttp = (Boolean) eh;
                Object td = root.get("token_defaults");
                if (td instanceof Map) {
                    Map<String, Object> tdm = (Map<String, Object>) td;
                    Object ttl = tdm.get("ttl");
                    if (ttl instanceof Number) cc.tokenDefaultTtl = ((Number) ttl).longValue();
                    Object wl = tdm.get("whitelist");
                    if (wl instanceof List) for (Object o : (List<Object>) wl) if (o != null) cc.tokenDefaultWhitelist.add(o.toString());
                    Object bl = tdm.get("blacklist");
                    if (bl instanceof List) for (Object o : (List<Object>) bl) if (o != null) cc.tokenDefaultBlacklist.add(o.toString());
                }
                // top-level merged token policy fields
                Object ask = root.get("allowed_skew_seconds");
                if (ask instanceof Number) cc.allowedSkewSeconds = ((Number) ask).intValue();
                Object dttl = root.get("default_token_ttl_seconds");
                if (dttl instanceof Number) cc.defaultTokenTtlSeconds = ((Number) dttl).longValue();
                // also support a nested `tokens` or `token_policy` map for backward-compat
                Object tokensMap = root.get("tokens");
                if (tokensMap instanceof Map) {
                    Map<String, Object> tm = (Map<String, Object>) tokensMap;
                    Object ask2 = tm.get("allowed_skew_seconds");
                    if (ask2 instanceof Number) cc.allowedSkewSeconds = ((Number) ask2).intValue();
                    Object dttl2 = tm.get("default_token_ttl_seconds");
                    if (dttl2 instanceof Number) cc.defaultTokenTtlSeconds = ((Number) dttl2).longValue();
                }
                Object gw = root.get("global_whitelist");
                if (gw instanceof List) for (Object o : (List<Object>) gw) if (o != null) cc.globalWhitelist.add(o.toString());
                Object gb = root.get("global_blacklist");
                if (gb instanceof List) for (Object o : (List<Object>) gb) if (o != null) cc.globalBlacklist.add(o.toString());
            }
        } catch (Exception ignored) {}
        return cc;
    }
}
