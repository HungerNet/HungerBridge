package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.Permissions;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Read-only {@code /world/*} endpoints. */
public final class WorldHandlers {

    private WorldHandlers() {}

    public static List<ApiHandler> all(Config config, Logger logger, CommandExecutor executor) {
        return List.of(
                new Tps(config, logger, executor),
                new Mspt(config, logger, executor),
                new Time(config, logger, executor),
                new Weather(config, logger, executor),
                new Counts(config, logger, executor, "/world/chunks", Permissions.WORLD_CHUNKS, true),
                new Counts(config, logger, executor, "/world/entities", Permissions.WORLD_ENTITIES, false));
    }

    /** GET /world/tps → tps (instant), tps_1m, tps_5m, tps_15m; -1 when unavailable. */
    public static final class Tps extends ApiHandler {
        private final CommandExecutor executor;

        public Tps(Config config, Logger logger, CommandExecutor executor) {
            super(config, logger, "/world/tps", "GET", Permissions.WORLD_TPS);
            this.executor = executor;
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true,
                    "tps", executor.getTps(), "tps_1m", executor.getTps1m(),
                    "tps_5m", executor.getTps5m(), "tps_15m", executor.getTps15m()));
        }
    }

    /** GET /world/mspt → average milliseconds per tick (-1 when unavailable). */
    public static final class Mspt extends ApiHandler {
        private final CommandExecutor executor;

        public Mspt(Config config, Logger logger, CommandExecutor executor) {
            super(config, logger, "/world/mspt", "GET", Permissions.WORLD_MSPT);
            this.executor = executor;
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "mspt", executor.getTickTimeMs()));
        }
    }

    /**
     * GET /world/time → overworld time: {@code time} (tick of day, 0–23999),
     * {@code full_time} (absolute day-time ticks), {@code day} and {@code server_time}
     * (wall clock, epoch seconds). Values are -1 when the world is not loaded yet.
     */
    public static final class Time extends ApiHandler {
        private final CommandExecutor executor;

        public Time(Config config, Logger logger, CommandExecutor executor) {
            super(config, logger, "/world/time", "GET", Permissions.WORLD_TIME);
            this.executor = executor;
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            long full = executor.getWorldFullTime();
            long time = full < 0 ? -1L : Math.floorMod(full, 24000L);
            long day = full < 0 ? -1L : Math.floorDiv(full, 24000L);
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "time", time, "full_time", full, "day", day,
                    "server_time", System.currentTimeMillis() / 1000L));
        }
    }

    /** GET /world/weather → {@code clear}, {@code rain} or {@code thunder} (overworld). */
    public static final class Weather extends ApiHandler {
        private final CommandExecutor executor;

        public Weather(Config config, Logger logger, CommandExecutor executor) {
            super(config, logger, "/world/weather", "GET", Permissions.WORLD_WEATHER);
            this.executor = executor;
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            String weather = executor.getWeather();
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "weather", weather == null || weather.isBlank() ? "clear" : weather));
        }
    }

    /**
     * GET /world/chunks and /world/entities → {@code total} plus the three vanilla
     * dimensions ({@code world}, {@code world_nether}, {@code world_the_end}). Worlds
     * that could not be sampled report -1 and are excluded from the total.
     */
    public static final class Counts extends ApiHandler {
        private final CommandExecutor executor;
        private final boolean chunks;

        Counts(Config config, Logger logger, CommandExecutor executor, String path, String permission, boolean chunks) {
            super(config, logger, path, "GET", permission);
            this.executor = executor;
            this.chunks = chunks;
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            Map<String, Integer> counts = chunks ? executor.getWorldChunkCounts() : executor.getWorldEntityCounts();
            int total = 0;
            for (Integer v : counts.values()) {
                if (v != null && v > 0) total += v;
            }
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true,
                    "total", total,
                    "world", counts.getOrDefault("world", 0),
                    "world_nether", counts.getOrDefault("world_nether", 0),
                    "world_the_end", counts.getOrDefault("world_the_end", 0)));
        }
    }
}
