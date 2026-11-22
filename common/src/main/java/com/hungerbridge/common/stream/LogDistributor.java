package com.hungerbridge.common.stream;

import com.hungerbridge.common.log.LineFormat;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The console event log of this JVM: a sequence-numbered ring of recent {@link LogEvent}s and
 * the fan-out to connected stream clients.
 *
 * <p>Guarantees (protocol v2):
 * <ul>
 *   <li>Every event gets the next sequence number; numbering restarts only with a new JVM,
 *       which also gets a new random {@link #epoch()}.</li>
 *   <li>{@link #subscribe} computes the replay and registers the client under the same lock
 *       that {@link #publish} holds, so a client receives every event exactly once and in
 *       order: replayed events first, then live events with higher sequence numbers.</li>
 *   <li>A client that falls {@value StreamConnection#QUEUE_SIZE} events behind is disconnected
 *       instead of silently losing events; it resumes from its last event id.</li>
 *   <li>Pickup passkeys are redacted before an event is stored or sent.</li>
 * </ul>
 */
public final class LogDistributor {

    public static final int RING_SIZE = 1000;
    public static final int MAX_CLIENTS = 64;
    static final long KEEPALIVE_SECONDS = 15;

    private static final Pattern PASSKEY = Pattern.compile("(?i)(passkey=)[0-9a-f]+");
    private static final LogDistributor INSTANCE = new LogDistributor();

    private final String epoch = newEpoch();
    private final Object lock = new Object();
    private final ArrayDeque<LogEvent> ring = new ArrayDeque<>(RING_SIZE);
    private final List<StreamConnection> clients = new ArrayList<>();
    private long seq;
    private ScheduledExecutorService keepalive;

    private LogDistributor() {}

    public static LogDistributor get() {
        return INSTANCE;
    }

    /** Random id of this JVM's event sequence. */
    public String epoch() {
        return epoch;
    }

    /** Sequence number of the newest event (0 before the first). */
    public long lastSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    public LogEvent publish(long time, String level, String thread, String logger, String message, String throwable) {
        String safeMessage = redact(message == null ? "" : message);
        String safeThrowable = throwable == null ? null : redact(throwable);
        String line = LineFormat.format(time, level, thread, logger, safeMessage, safeThrowable);
        synchronized (lock) {
            LogEvent event = new LogEvent(++seq, time, level, thread, logger, safeMessage, safeThrowable, line);
            ring.addLast(event);
            while (ring.size() > RING_SIZE) ring.removeFirst();
            for (StreamConnection client : List.copyOf(clients)) client.offer(event);
            return event;
        }
    }

    /** Where a new connection starts. */
    public record Resume(int history, String epoch, long afterSeq) {
        public static final int ALL = -1;

        /** First connect: replay the {@code count} newest events ({@link #ALL} = the whole ring). */
        public static Resume history(int count) {
            return new Resume(count, null, 0L);
        }

        /** Reconnect after the event {@code epoch.afterSeq}. */
        public static Resume after(String epoch, long afterSeq) {
            return new Resume(0, epoch, afterSeq);
        }
    }

    /**
     * A registered client.
     *
     * @param replay events to send before any live event
     * @param gap    true if events after the resume point are no longer in the ring
     * @param seq    sequence number of the newest event at subscription time
     */
    public record Subscription(StreamConnection connection, List<LogEvent> replay, boolean gap, long seq) {}

    public static final class TooManyClientsException extends Exception {
        TooManyClientsException() {
            super("Too many active log streams (" + MAX_CLIENTS + ")");
        }
    }

    public Subscription subscribe(Resume resume) throws TooManyClientsException {
        synchronized (lock) {
            if (clients.size() >= MAX_CLIENTS) throw new TooManyClientsException();
            List<LogEvent> replay = new ArrayList<>();
            boolean gap = false;
            if (resume.epoch() == null) {
                int count = resume.history() == Resume.ALL ? ring.size() : Math.min(Math.max(0, resume.history()), ring.size());
                int skip = ring.size() - count;
                for (LogEvent e : ring) {
                    if (skip-- <= 0) replay.add(e);
                }
            } else {
                long after = epoch.equals(resume.epoch()) ? resume.afterSeq() : 0L;
                for (LogEvent e : ring) {
                    if (e.seq() > after) replay.add(e);
                }
                long firstAvailable = ring.isEmpty() ? seq + 1 : ring.peekFirst().seq();
                gap = after < seq && firstAvailable > after + 1;
            }
            StreamConnection connection = new StreamConnection(this);
            clients.add(connection);
            ensureKeepalive();
            return new Subscription(connection, replay, gap, seq);
        }
    }

    void unregister(StreamConnection connection) {
        synchronized (lock) {
            clients.remove(connection);
        }
    }

    public int clientCount() {
        synchronized (lock) {
            return clients.size();
        }
    }

    /** Disconnects every client and stops the keepalive timer (bridge shutdown). */
    public void close() {
        List<StreamConnection> all;
        synchronized (lock) {
            all = List.copyOf(clients);
            clients.clear();
            if (keepalive != null) {
                keepalive.shutdownNow();
                keepalive = null;
            }
        }
        for (StreamConnection c : all) c.close();
    }

    private void ensureKeepalive() {
        if (keepalive != null) return;
        keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HungerBridge-Stream-Keepalive");
            t.setDaemon(true);
            return t;
        });
        keepalive.scheduleAtFixedRate(() -> {
            List<StreamConnection> all;
            synchronized (lock) {
                all = List.copyOf(clients);
            }
            for (StreamConnection c : all) c.offer(StreamConnection.KEEPALIVE);
        }, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    private static String newEpoch() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String redact(String text) {
        return text.indexOf('=') >= 0 ? PASSKEY.matcher(text).replaceAll("$1[REDACTED]") : text;
    }
}
