package com.hungerbridge.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Permission nodes checked by the HTTP API. Wildcards: {@code server.*}, {@code world.*}, {@code system.*}, {@code *}. */
public final class Permissions {

    public static final String SERVER_PING = "server.ping";
    public static final String SERVER_META = "server.meta";
    public static final String SERVER_RUN = "server.run";
    public static final String SERVER_STOP = "server.stop";
    public static final String SERVER_LOG = "server.log";
    public static final String SERVER_STREAM = "server.stream";
    public static final String PLAYERS_LIST = "players.list";
    public static final String WORLD_TPS = "world.tps";
    public static final String WORLD_MSPT = "world.mspt";
    public static final String WORLD_TIME = "world.time";
    public static final String WORLD_WEATHER = "world.weather";
    public static final String WORLD_CHUNKS = "world.chunks";
    public static final String WORLD_ENTITIES = "world.entities";
    public static final String SYSTEM_UPTIME = "system.uptime";
    public static final String SYSTEM_CPU = "system.cpu";
    public static final String SYSTEM_MEMORY = "system.memory";
    public static final String SYSTEM_GC = "system.gc";
    public static final String SYSTEM_THREADS = "system.threads";
    public static final String SYSTEM_NETWORK = "system.network";

    /** Every node an endpoint checks. */
    public static final Set<String> KNOWN = Set.of(
            SERVER_PING, SERVER_META, SERVER_RUN, SERVER_STOP, SERVER_LOG, SERVER_STREAM,
            PLAYERS_LIST,
            WORLD_TPS, WORLD_MSPT, WORLD_TIME, WORLD_WEATHER, WORLD_CHUNKS, WORLD_ENTITIES,
            SYSTEM_UPTIME, SYSTEM_CPU, SYSTEM_MEMORY, SYSTEM_GC, SYSTEM_THREADS, SYSTEM_NETWORK);

    /** Trimmed, de-duplicated nodes in their original order. */
    public static List<String> normalize(Collection<String> granted) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (granted == null) return new ArrayList<>();
        for (String raw : granted) {
            if (raw != null && !raw.isBlank()) out.add(raw.trim());
        }
        return new ArrayList<>(out);
    }

    /** True if {@code node} can ever match an endpoint: a known node or a wildcard covering one. */
    public static boolean isMeaningful(String node) {
        if (node == null || node.isBlank()) return false;
        String p = node.trim();
        if ("*".equals(p) || KNOWN.contains(p)) return true;
        if (p.endsWith(".*")) {
            String prefix = p.substring(0, p.length() - 1);
            return KNOWN.stream().anyMatch(k -> k.startsWith(prefix));
        }
        return false;
    }

    private Permissions() {}
}
