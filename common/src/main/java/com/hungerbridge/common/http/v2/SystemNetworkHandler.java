package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class SystemNetworkHandler implements HttpHandler {
    private static final Path PROC_NET_DEV = Path.of("/proc/net/dev");
    private static final AtomicLong LAST_RX = new AtomicLong(-1L);
    private static final AtomicLong LAST_TX = new AtomicLong(-1L);
    private static final AtomicLong LAST_SAMPLE_AT = new AtomicLong(0L);

    private final Config config;
    private final Logger logger;

    public SystemNetworkHandler(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtil.error(ex, 405, "method_not_allowed", "Use GET", config);
            return;
        }
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "system.network")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access network", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "system.network")) return;

        long[] totals = readProcNetDevTotals();
        long now = System.currentTimeMillis();
        long rxTotal = totals[0];
        long txTotal = totals[1];

        long lastRx = LAST_RX.get();
        long lastTx = LAST_TX.get();
        long lastAt = LAST_SAMPLE_AT.get();

        long deltaRx = 0L;
        long deltaTx = 0L;
        if (lastRx >= 0L && lastTx >= 0L && lastAt > 0L) {
            long elapsedMs = Math.max(1L, now - lastAt);
            deltaRx = Math.max(0L, (rxTotal - lastRx) * 1000L / elapsedMs);
            deltaTx = Math.max(0L, (txTotal - lastTx) * 1000L / elapsedMs);
        }

        LAST_RX.set(rxTotal);
        LAST_TX.set(txTotal);
        LAST_SAMPLE_AT.set(now);

        JsonObject resp = Json.obj(
                "ok", true,
                "bytes_in_per_sec", deltaRx,
                "bytes_out_per_sec", deltaTx,
                "total_bytes_in", rxTotal,
                "total_bytes_out", txTotal
        );
        HttpUtil.writeJson(ex, 200, resp);
    }

    private static long[] readProcNetDevTotals() {
        long rx = 0L;
        long tx = 0L;
        try {
            if (!Files.exists(PROC_NET_DEV)) {
                return new long[] {0L, 0L};
            }
            try (BufferedReader reader = Files.newBufferedReader(PROC_NET_DEV)) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (first) {
                        first = false;
                        continue;
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length < 10) continue;
                    try {
                        long received = Long.parseLong(parts[1]);
                        long sent = Long.parseLong(parts[9]);
                        rx += received;
                        tx += sent;
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed interface rows.
                    }
                }
            }
        } catch (IOException ignored) {
            return new long[] {0L, 0L};
        }
        return new long[] {rx, tx};
    }
}
