package com.hungerbridge.common;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared broadcaster for live log lines over SSE connections.
 */
public final class LogDistributor {

    private static final LogDistributor INSTANCE = new LogDistributor();

    private final CopyOnWriteArrayList<StreamConnection> clients = new CopyOnWriteArrayList<>();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HungerBridge");
        t.setDaemon(true);
        return t;
    });
    // in-memory history buffer for recent log lines (sent to new connections)
    private final java.util.Deque<String> history = new java.util.ArrayDeque<>();
    private static final int HISTORY_MAX = 200;

    private LogDistributor() {}

    public static LogDistributor get() {
        return INSTANCE;
    }

    public StreamConnection register(OutputStream out) {
        if (clients.size() >= 64) {
            throw new IllegalStateException("Too many active log stream clients");
        }
        StreamConnection connection = new StreamConnection(out, scheduler);
        clients.add(connection);
        return connection;
    }

    /**
     * Send up to `count` most-recent history lines to the provided connection.
     * If count is larger than available history, all history is sent.
     */
    public void sendHistory(StreamConnection connection, int count) {
        if (connection == null || count <= 0) return;
        java.util.List<String> snapshot;
        synchronized (history) {
            snapshot = new java.util.ArrayList<>(history);
        }
        int start = Math.max(0, snapshot.size() - Math.min(count, HISTORY_MAX));
        for (int i = start; i < snapshot.size(); i++) {
            connection.write("data:" + escapeSse(snapshot.get(i)) + "\n\n");
        }
    }

    public void unregister(StreamConnection connection) {
        if (connection == null) {
            return;
        }
        clients.remove(connection);
        connection.close();
    }

    public void publish(String line) {
        if (line == null) {
            return;
        }

        // sanitize line for broadcasting (remove any pickup/passkey secrets)
        String sanitized = sanitizeForBroadcast(line);

        // record sanitized line in history
        synchronized (history) {
            history.addLast(sanitized);
            if (history.size() > HISTORY_MAX) {
                history.removeFirst();
            }
        }

        String payload = "data:" + escapeSse(sanitized) + "\n\n";
        for (StreamConnection client : clients) {
            client.write(payload);
        }
    }

    private static String sanitizeForBroadcast(String line) {
        if (line == null) return null;
        String out = line;
        // redact explicit pickup passkeys: "Pickup passkey: <hex>"
        out = out.replaceAll("(?i)(Pickup passkey:\\s*)([0-9a-fA-F]+)", "$1[REDACTED]");
        // redact pickup URL passkey query parameter: "/pickup/<id>?passkey=<hex>"
        out = out.replaceAll("(?i)(/pickup/[^\\s\\?]+\\?passkey=)([0-9a-fA-F]+)", "$1[REDACTED]");
        // redact any occurrence of "passkey=" query values
        out = out.replaceAll("(?i)(passkey=)([0-9a-fA-F]+)", "$1[REDACTED]");
        return out;
    }

    public void close() {
        for (StreamConnection client : clients) {
            client.close();
        }
        clients.clear();
        scheduler.shutdownNow();
    }

    private static String escapeSse(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    public static final class StreamConnection implements AutoCloseable {
        private static final int MAX_QUEUE_SIZE = 256;
        private static final long STALLED_MILLIS = 30_000L;

        private final OutputStream output;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final ScheduledFuture<?> keepaliveTask;
        private final java.util.concurrent.BlockingQueue<String> queue = new java.util.concurrent.ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
        private volatile long lastActivityNanos = System.nanoTime();

        private StreamConnection(OutputStream output, ScheduledExecutorService scheduler) {
            this.output = output;
            this.keepaliveTask = scheduler.scheduleAtFixedRate(
                    () -> {
                        if (active.get()) {
                            write(":keepalive\n\n");
                        }
                    },
                    15,
                    15,
                    TimeUnit.SECONDS
            );
        }

        public void await() throws InterruptedException {
            closed.await();
        }

        public java.util.concurrent.BlockingQueue<String> getQueue() {
            return queue;
        }

        public boolean isActive() {
            return active.get();
        }

        public void write(String chunk) {
            if (!active.get() || chunk == null) {
                return;
            }
            if (queue.size() >= MAX_QUEUE_SIZE) {
                queue.poll();
            }
            while (!queue.offer(chunk)) {
                queue.poll();
            }
            lastActivityNanos = System.nanoTime();
        }

        public boolean isStalled() {
            long idleMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivityNanos);
            return idleMillis > STALLED_MILLIS;
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            if (keepaliveTask != null) {
                keepaliveTask.cancel(false);
            }
            // wake up any waiting handler by offering an empty string
            queue.offer("");
            closed.countDown();
        }
    }
}
