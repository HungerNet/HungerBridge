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
        Thread t = new Thread(r, "hungerbridge-log-distributor");
        t.setDaemon(true);
        return t;
    });

    private LogDistributor() {}

    public static LogDistributor get() {
        return INSTANCE;
    }

    public StreamConnection register(OutputStream out) {
        StreamConnection connection = new StreamConnection(out, scheduler);
        clients.add(connection);
        return connection;
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

        String payload = "data:" + escapeSse(line) + "\n\n";
        for (StreamConnection client : clients) {
            client.write(payload);
        }
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
        private final OutputStream output;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final ScheduledFuture<?> keepaliveTask;

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

        public void write(String chunk) {
            if (!active.get()) {
                return;
            }

            try {
                output.write(chunk.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException e) {
                close();
            }
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            if (keepaliveTask != null) {
                keepaliveTask.cancel(false);
            }
            try {
                output.close();
            } catch (IOException ignored) {
                // Client is already gone or the socket is closed.
            }
            closed.countDown();
        }
    }
}
