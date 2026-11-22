package com.hungerbridge.common.testing;

import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.CommandTimeoutException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Deterministic stand-in for a Minecraft server, used by tests and the standalone bridge.
 * Like the real platforms, feedback reaches the console ({@code console}) only when
 * {@code showConsole} is true.
 */
public class FakeExecutor implements CommandExecutor {

    /** Command text that simulates a server thread that does not answer in time. */
    public static final String TIMEOUT_COMMAND = "hb-test-timeout";

    public final List<String> executed = new CopyOnWriteArrayList<>();
    public final List<Boolean> showConsoleFlags = new CopyOnWriteArrayList<>();
    public volatile List<String> players = new ArrayList<>();
    private final Consumer<String> console;
    private final long startedAt = System.currentTimeMillis();

    public FakeExecutor() {
        this(line -> {});
    }

    /** @param console receives feedback lines echoed to the console */
    public FakeExecutor(Consumer<String> console) {
        this.console = console;
    }

    @Override
    public List<String> run(String command, boolean showConsole) throws CommandTimeoutException {
        executed.add(command);
        showConsoleFlags.add(showConsole);
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        if (cmd.equals(TIMEOUT_COMMAND)) throw new CommandTimeoutException(command, COMMAND_TIMEOUT_MS);
        List<String> feedback;
        if (cmd.equals("list")) {
            feedback = List.of("There are " + players.size() + " of a max of 20 players online: " + String.join(", ", players));
        } else if (cmd.equals("seed")) {
            feedback = List.of("Seed: [1234567890]");
        } else if (cmd.startsWith("say ")) {
            feedback = List.of("[Server] " + cmd.substring(4));
        } else {
            feedback = List.of("Unknown or incomplete command: " + cmd);
        }
        if (showConsole) feedback.forEach(console);
        return feedback;
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
