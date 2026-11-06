package com.hungerbridge.common.testing;

import com.hungerbridge.common.CommandExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic stand-in for a Minecraft server, used by tests and the standalone bridge. */
public class FakeExecutor implements CommandExecutor {

    public final List<String> executed = new CopyOnWriteArrayList<>();
    public volatile List<String> players = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();

    @Override
    public void execute(String command) {
        executed.add(command);
    }

    @Override
    public List<String> executeWithOutput(String command, boolean showConsole) {
        executed.add(command);
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        if (cmd.equals("list")) {
            return List.of("There are " + players.size() + " of a max of 20 players online: " + String.join(", ", players));
        }
        if (cmd.startsWith("say ")) {
            return List.of("[Server] " + cmd.substring(4));
        }
        return List.of("Unknown or incomplete command: " + cmd);
    }

    @Override public double getTps() { return 20.0; }
    @Override public double getTps1m() { return 19.9; }
    @Override public double getTps5m() { return 19.8; }
    @Override public double getTps15m() { return 19.7; }
    @Override public double getTickTimeMs() { return 1.25; }
    @Override public List<String> getOnlinePlayerNames() { return Collections.unmodifiableList(players); }
    @Override public int getMaxPlayers() { return 20; }

    @Override
    public Map<String, Integer> getWorldChunkCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("world", 441);
        m.put("world_nether", 0);
        m.put("world_the_end", 0);
        return m;
    }

    @Override
    public Map<String, Integer> getWorldEntityCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("world", 12);
        m.put("world_nether", 0);
        m.put("world_the_end", 0);
        return m;
    }

    @Override
    public long getWorldFullTime() {
        // 20 ticks per second since start, starting at "day 1, 1000"
        return 24000L + 1000L + (System.currentTimeMillis() - startedAt) / 50L;
    }

    @Override
    public String getWeather() {
        return "clear";
    }
}
