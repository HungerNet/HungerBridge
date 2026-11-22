package com.hungerbridge.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What a platform (Paper/Folia, Fabric) provides to the HTTP API: command execution and
 * read-only game metrics. All game state is accessed on the thread that owns it, with a
 * timeout, so a stalled server never hangs an HTTP request.
 */
public interface CommandExecutor {

    /** Upper bound for running one command on the server thread. */
    long COMMAND_TIMEOUT_MS = 5_000L;

    /**
     * Runs {@code command} with full (console-level) permissions and returns its feedback.
     *
     * <p>Feedback is captured from a dedicated command source, not from the console log.
     * With {@code showConsole == false} nothing of the feedback is written to the server
     * console; with {@code true} each feedback line is also sent to the console exactly as
     * the console's own commands are. Game side effects that the server logs by itself (for
     * example the chat broadcast of {@code /say}) are not command feedback.
     *
     * @return feedback lines in order (possibly empty), as plain text
     * @throws CommandTimeoutException the server thread did not run the command within
     *                                 {@link #COMMAND_TIMEOUT_MS}
     */
    List<String> run(String command, boolean showConsole) throws CommandTimeoutException;

    /** Current TPS (last 20 ticks or equivalent), -1 if unavailable. */
    default double getTps() {
        return -1.0;
    }

    default double getTps1m() {
        return -1.0;
    }

    default double getTps5m() {
        return -1.0;
    }

    default double getTps15m() {
        return -1.0;
    }

    /** Average tick time in milliseconds, -1 if unavailable. */
    default double getTickTimeMs() {
        return -1.0;
    }

    default List<String> getOnlinePlayerNames() {
        return Collections.emptyList();
    }

    default int getMaxPlayers() {
        return 0;
    }

    /** Loaded chunk counts by protocol world key ({@code world}, {@code world_nether}, ...). */
    default Map<String, Integer> getWorldChunkCounts() {
        return Collections.emptyMap();
    }

    default Map<String, Integer> getWorldEntityCounts() {
        return Collections.emptyMap();
    }

    /**
     * Absolute overworld day-time in ticks ({@code /time query daytime} without the modulo),
     * or -1 if unavailable.
     */
    default long getWorldFullTime() {
        return -1L;
    }

    /** {@code clear}, {@code rain} or {@code thunder}. */
    default String getWeather() {
        return "clear";
    }
}
