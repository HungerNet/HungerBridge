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
    public List<String> commandAliases = new ArrayList<>();
    public long tokenDefaultTtl = 3600;
    public List<String> tokenDefaultWhitelist = new ArrayList<>();
    public List<String> tokenDefaultBlacklist = new ArrayList<>();

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
                Object ec = root.get("enable_commands");
                if (ec instanceof Boolean) cc.enableCommands = (Boolean) ec;
                Object eh = root.get("enable_admin_http");
                if (eh instanceof Boolean) cc.enableAdminHttp = (Boolean) eh;
                Object aliases = root.get("command_aliases");
                if (aliases instanceof List) for (Object o : (List<Object>) aliases) if (o != null) cc.commandAliases.add(o.toString());
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
                Object gw = root.get("global_whitelist");
                if (gw instanceof List) for (Object o : (List<Object>) gw) if (o != null) cc.globalWhitelist.add(o.toString());
                Object gb = root.get("global_blacklist");
                if (gb instanceof List) for (Object o : (List<Object>) gb) if (o != null) cc.globalBlacklist.add(o.toString());
            }
        } catch (Exception ignored) {}
        return cc;
    }
}
