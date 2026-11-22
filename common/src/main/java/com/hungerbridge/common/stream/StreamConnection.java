package com.hungerbridge.common.stream;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-client queue between {@link LogDistributor} (any thread) and the client's writer
 * thread. Offering never blocks; a full queue disconnects the client ({@link #overflowed()}),
 * which then resumes from its last event id instead of silently missing events.
 *
 * <p>Note: the JDK {@code HttpServer} offers no way to interrupt a writer blocked on a peer
 * that stopped reading; such a thread is released when the TCP connection fails. The
 * {@value LogDistributor#MAX_CLIENTS}-client cap bounds the impact.
 */
public final class StreamConnection implements AutoCloseable {

    public static final int QUEUE_SIZE = 1024;

    /** Queue item asking the writer to send an SSE comment. */
    static final Object KEEPALIVE = new Object();
    private static final Object CLOSED = new Object();

    private final LogDistributor owner;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_SIZE + 1);
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile boolean overflowed;

    StreamConnection(LogDistributor owner) {
        this.owner = owner;
    }

    void offer(Object item) {
        if (!active.get()) return;
        if (queue.size() >= QUEUE_SIZE || !queue.offer(item)) {
            overflowed = true;
            close();
        }
    }

    /**
     * Next item for the writer: a {@link LogEvent}, {@link #isKeepalive keepalive}, or
     * {@code null} on timeout or when the connection is closed (check {@link #isActive()}).
     */
    public Object next(long timeout, TimeUnit unit) throws InterruptedException {
        Object item = queue.poll(timeout, unit);
        return item == CLOSED ? null : item;
    }

    public static boolean isKeepalive(Object item) {
        return item == KEEPALIVE;
    }

    public boolean isActive() {
        return active.get();
    }

    /** True if the client was disconnected because it fell too far behind. */
    public boolean overflowed() {
        return overflowed;
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) return;
        owner.unregister(this);
        queue.clear();
        queue.offer(CLOSED); // wake the writer
    }
}
