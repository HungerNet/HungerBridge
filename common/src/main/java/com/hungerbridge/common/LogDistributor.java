package com.hungerbridge.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fan-out of log lines to SSE clients, plus a bounded in-memory history.
 *
 * <p>Wire format per line: {@code data:<line>\n\n} with {@code \r}/{@code \n} inside the
 * line escaped as the two characters {@code \r}/{@code \n}. Pickup passkeys are redacted
 * before anything is stored or sent.
 */
public final class LogDistributor {

    private static final LogDistributor INSTANCE = new LogDistributor();
    private static final int HISTORY_MAX = 200;
    private static final int MAX_CLIENTS = 64;
    static final String KEEPALIVE = ":keepalive\n\n";

    private final CopyOnWriteArrayList<StreamConnection> clients = new CopyOnWriteArrayList<>();
    private final Deque<String> history = new ArrayDeque<>();
    private ScheduledExecutorService scheduler;

    private LogDistributor() {}

    public static LogDistributor get() {
        return INSTANCE;
    }

    private synchronized ScheduledExecutorService scheduler() {
        // Re-created lazily: close() shuts it down when the bridge stops, and the bridge
        // may be started again in the same JVM (plugin reload, tests).
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HungerBridge-SSE-Keepalive");
                t.setDaemon(true);
                return t;
            });
        }
        return scheduler;
    }

    /** @throws IllegalStateException when {@value #MAX_CLIENTS} clients are already connected */
    public synchronized StreamConnection register() {
        if (clients.size() >= MAX_CLIENTS) {
            throw new IllegalStateException("Too many active log stream clients");
        }
        StreamConnection connection = new StreamConnection(scheduler());
        clients.add(connection);
        return connection;
    }

    /** Queue the {@code count} most recent history lines (oldest first) for {@code connection}. */
    public void sendHistory(StreamConnection connection, int count) {
        if (connection == null || count <= 0) return;
        List<String> snapshot;
        synchronized (history) {
            snapshot = new ArrayList<>(history);
        }
        int start = Math.max(0, snapshot.size() - Math.min(count, HISTORY_MAX));
        for (int i = start; i < snapshot.size(); i++) {
            connection.write(frame(snapshot.get(i)));
        }
    }

    public void unregister(StreamConnection connection) {
        if (connection == null) return;
        clients.remove(connection);
        connection.close();
    }

    public void publish(String line) {
        if (line == null) return;
        String sanitized = sanitizeForBroadcast(line);
        synchronized (history) {
            history.addLast(sanitized);
            while (history.size() > HISTORY_MAX) history.removeFirst();
        }
        String payload = frame(sanitized);
        for (StreamConnection client : clients) {
            client.write(payload);
        }
    }

    public int clientCount() {
        return clients.size();
    }

    static String frame(String line) {
        return "data:" + line.replace("\r", "\\r").replace("\n", "\\n") + "\n\n";
    }

    static String sanitizeForBroadcast(String line) {
        String out = line;
        out = out.replaceAll("(?i)(Pickup passkey:\\s*)([0-9a-fA-F]+)", "$1[REDACTED]");
        out = out.replaceAll("(?i)(passkey=)([0-9a-fA-F]+)", "$1[REDACTED]");
        return out;
    }

    public synchronized void close() {
        for (StreamConnection client : clients) {
            client.close();
        }
        clients.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public static final class StreamConnection implements AutoCloseable {
        private static final int MAX_QUEUE_SIZE = 256;
        private static final long STALLED_MILLIS = 60_000L;

        private final AtomicBoolean active = new AtomicBoolean(true);
        private final ScheduledFuture<?> keepaliveTask;
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
        private volatile long lastActivityNanos = System.nanoTime();

        private StreamConnection(ScheduledExecutorService scheduler) {
            this.keepaliveTask = scheduler.scheduleAtFixedRate(() -> write(KEEPALIVE), 15, 15, TimeUnit.SECONDS);
        }

        public BlockingQueue<String> getQueue() {
            return queue;
        }

        public boolean isActive() {
            return active.get();
        }

        /** Non-blocking; drops the oldest queued chunk when a slow client falls behind. */
        public void write(String chunk) {
            if (!active.get() || chunk == null) return;
            while (!queue.offer(chunk)) {
                queue.poll();
            }
            lastActivityNanos = System.nanoTime();
        }

        public boolean isStalled() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivityNanos) > STALLED_MILLIS;
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) return;
            keepaliveTask.cancel(false);
            queue.offer(""); // wake the writer thread
        }
    }
}
