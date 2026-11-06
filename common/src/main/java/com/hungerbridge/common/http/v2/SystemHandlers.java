package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.ProcessMemoryProvider;
import com.hungerbridge.common.ProcessMemoryProviderFactory;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.Permissions;
import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Read-only JVM / host metrics under {@code /system/*}. Platform independent. */
public final class SystemHandlers {

    private SystemHandlers() {}

    public static List<ApiHandler> all(Config config, Logger logger) {
        return List.of(new Uptime(config, logger), new Cpu(config, logger), new Memory(config, logger),
                new Gc(config, logger), new Threads(config, logger), new Network(config, logger));
    }

    /** GET /system/uptime → {@code uptime_ms} of the JVM. */
    public static final class Uptime extends ApiHandler {
        public Uptime(Config config, Logger logger) {
            super(config, logger, "/system/uptime", "GET", Permissions.SYSTEM_UPTIME);
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime()));
        }
    }

    /** GET /system/cpu → {@code cpu_load} (process, 0..1; host load as fallback) and {@code processors}. */
    public static final class Cpu extends ApiHandler {
        public Cpu(Config config, Logger logger) {
            super(config, logger, "/system/cpu", "GET", Permissions.SYSTEM_CPU);
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "cpu_load", cpuLoad(processors), "processors", processors));
        }

        static double cpuLoad(int processors) {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            double load = -1.0;
            try {
                if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                    load = sun.getProcessCpuLoad();
                    if (load < 0.0) load = sun.getCpuLoad();
                }
                if (load < 0.0 && os.getSystemLoadAverage() >= 0.0) {
                    load = os.getSystemLoadAverage() / processors;
                }
            } catch (RuntimeException ignored) {
                load = -1.0;
            }
            if (!Double.isFinite(load) || load < 0.0) return 0.0;
            return Math.min(1.0, load);
        }
    }

    /**
     * GET /system/memory → heap / non-heap / JVM / process bytes. {@code nonheap_max_bytes}
     * is {@code null} when unbounded. Legacy aliases {@code used_bytes}, {@code total_bytes},
     * {@code free_bytes}, {@code max_bytes} refer to the heap.
     */
    public static final class Memory extends ApiHandler {
        public Memory(Config config, Logger logger) {
            super(config, logger, "/system/memory", "GET", Permissions.SYSTEM_MEMORY);
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
            long heapUsed = Math.max(0L, heap.getUsed());
            long heapCommitted = Math.max(0L, heap.getCommitted());
            long heapMax = heap.getMax() >= 0 ? heap.getMax() : heapCommitted;
            long nonheapUsed = Math.max(0L, nonHeap.getUsed());
            long nonheapCommitted = Math.max(0L, nonHeap.getCommitted());
            Long nonheapMax = nonHeap.getMax() >= 0L ? nonHeap.getMax() : null;

            long processUsed = 0L;
            long processVirtual = 0L;
            try {
                ProcessMemoryProvider provider = ProcessMemoryProviderFactory.create();
                processUsed = Math.max(0L, provider.getProcessUsedBytes());
                processVirtual = Math.max(0L, provider.getProcessVirtualBytes());
            } catch (RuntimeException ignored) {
                // unsupported platform → 0
            }

            HttpUtil.writeJson(ex, 200, Json.obj(
                    "ok", true,
                    "heap_used_bytes", heapUsed,
                    "heap_committed_bytes", heapCommitted,
                    "heap_max_bytes", heapMax,
                    "nonheap_used_bytes", nonheapUsed,
                    "nonheap_committed_bytes", nonheapCommitted,
                    "nonheap_max_bytes", nonheapMax,
                    "jvm_used_bytes", heapUsed + nonheapUsed,
                    "jvm_committed_bytes", heapCommitted + nonheapCommitted,
                    "jvm_max_bytes", nonheapMax == null ? heapMax : heapMax + nonheapMax,
                    "process_used_bytes", processUsed,
                    "process_virtual_bytes", processVirtual,
                    "used_bytes", heapUsed,
                    "total_bytes", heapCommitted,
                    "free_bytes", Math.max(0L, heapCommitted - heapUsed),
                    "max_bytes", heapMax));
        }
    }

    /**
     * GET /system/gc → {@code gc_type} (collector family: G1, ZGC, Shenandoah, Parallel,
     * Serial), {@code gc_count}, {@code gc_time_ms}, {@code last_gc_pause_ms} (duration of
     * the most recent collection) and {@code avg_gc_pause_ms}.
     */
    public static final class Gc extends ApiHandler {
        public Gc(Config config, Logger logger) {
            super(config, logger, "/system/gc", "GET", Permissions.SYSTEM_GC);
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            long count = 0L;
            long timeMs = 0L;
            long lastPause = 0L;
            long lastEnd = -1L;
            String type = "unknown";
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean.getCollectionCount() > 0L) count += bean.getCollectionCount();
                if (bean.getCollectionTime() > 0L) timeMs += bean.getCollectionTime();
                if ("unknown".equals(type)) type = collectorFamily(bean.getName());
                if (bean instanceof com.sun.management.GarbageCollectorMXBean sun) {
                    try {
                        com.sun.management.GcInfo info = sun.getLastGcInfo();
                        if (info != null && info.getEndTime() > lastEnd) {
                            lastEnd = info.getEndTime();
                            lastPause = info.getDuration();
                        }
                    } catch (RuntimeException ignored) {
                        // some collectors do not expose GcInfo
                    }
                }
            }
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "gc_type", type, "gc_count", count, "gc_time_ms", timeMs,
                    "last_gc_pause_ms", lastPause, "avg_gc_pause_ms", count > 0 ? timeMs / count : 0L));
        }

        static String collectorFamily(String beanName) {
            if (beanName == null) return "unknown";
            String n = beanName.toLowerCase(Locale.ROOT);
            if (n.startsWith("g1")) return "G1";
            if (n.startsWith("zgc")) return "ZGC";
            if (n.startsWith("shenandoah")) return "Shenandoah";
            if (n.startsWith("ps ")) return "Parallel";
            if (n.equals("copy") || n.equals("marksweepcompact")) return "Serial";
            if (n.startsWith("epsilon")) return "Epsilon";
            return beanName;
        }
    }

    /** GET /system/threads → {@code current}, {@code peak}, {@code daemon}. */
    public static final class Threads extends ApiHandler {
        public Threads(Config config, Logger logger) {
            super(config, logger, "/system/threads", "GET", Permissions.SYSTEM_THREADS);
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            ThreadMXBean t = ManagementFactory.getThreadMXBean();
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "current", t.getThreadCount(), "peak", t.getPeakThreadCount(), "daemon", t.getDaemonThreadCount()));
        }
    }

    /**
     * GET /system/network → host totals from {@code /proc/net/dev} (Linux; 0 elsewhere) and
     * per-second rates since the previous call.
     */
    public static final class Network extends ApiHandler {
        private static final Path PROC_NET_DEV = Path.of("/proc/net/dev");
        private long lastRx = -1L;
        private long lastTx = -1L;
        private long lastAt = 0L;

        public Network(Config config, Logger logger) {
            super(config, logger, "/system/network", "GET", Permissions.SYSTEM_NETWORK);
        }

        @Override
        protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
            long[] totals = readTotals();
            long now = System.currentTimeMillis();
            long inRate = 0L;
            long outRate = 0L;
            synchronized (this) {
                if (lastRx >= 0L && lastAt > 0L) {
                    long elapsed = Math.max(1L, now - lastAt);
                    inRate = Math.max(0L, (totals[0] - lastRx) * 1000L / elapsed);
                    outRate = Math.max(0L, (totals[1] - lastTx) * 1000L / elapsed);
                }
                lastRx = totals[0];
                lastTx = totals[1];
                lastAt = now;
            }
            HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "bytes_in_per_sec", inRate, "bytes_out_per_sec", outRate,
                    "total_bytes_in", totals[0], "total_bytes_out", totals[1]));
        }

        private static long[] readTotals() {
            long rx = 0L;
            long tx = 0L;
            if (!Files.exists(PROC_NET_DEV)) return new long[] {0L, 0L};
            try (BufferedReader reader = Files.newBufferedReader(PROC_NET_DEV)) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    if (lineNo++ < 2) continue; // two header lines
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String iface = line.substring(0, colon).trim();
                    if (iface.equals("lo")) continue;
                    String[] parts = line.substring(colon + 1).trim().split("\\s+");
                    if (parts.length < 9) continue;
                    try {
                        rx += Long.parseLong(parts[0]);
                        tx += Long.parseLong(parts[8]);
                    } catch (NumberFormatException ignored) {
                        // malformed row
                    }
                }
            } catch (IOException ignored) {
                return new long[] {0L, 0L};
            }
            return new long[] {rx, tx};
        }
    }
}
