package com.hungerbridge.common;

import com.hungerbridge.common.stream.LogDistributor;
import com.hungerbridge.common.stream.LogEvent;
import com.hungerbridge.common.stream.StreamConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Sequence numbers, replay/resume semantics and overflow handling of the event log. */
public final class LogDistributorTest {

    private final LogDistributor log = LogDistributor.get();
    private final List<StreamConnection> open = new ArrayList<>();

    @AfterEach
    void closeConnections() {
        open.forEach(StreamConnection::close);
    }

    private LogEvent publish(String message) {
        return log.publish(System.currentTimeMillis(), "INFO", "test", "Test", message, null);
    }

    private LogDistributor.Subscription subscribe(LogDistributor.Resume resume) throws Exception {
        LogDistributor.Subscription s = log.subscribe(resume);
        open.add(s.connection());
        return s;
    }

    private static List<String> drain(StreamConnection c, int count) throws Exception {
        List<String> out = new ArrayList<>();
        while (out.size() < count) {
            Object item = c.next(2, TimeUnit.SECONDS);
            if (item == null) break;
            if (item instanceof LogEvent e) out.add(e.message());
        }
        return out;
    }

    @Test
    public void sequenceNumbersIncreaseAndSecretsAreRedacted() {
        LogEvent a = publish("one");
        LogEvent b = publish("see /pickup/x?passkey=DEADBEEF");
        assertEquals(a.seq() + 1, b.seq());
        assertEquals("see /pickup/x?passkey=[REDACTED]", b.message());
        assertTrue(b.line().endsWith("[Test] see /pickup/x?passkey=[REDACTED]"), b.line());
    }

    @Test
    public void historyReplaysTheNewestEvents() throws Exception {
        publish("h1");
        publish("h2");
        publish("h3");
        LogDistributor.Subscription s = subscribe(LogDistributor.Resume.history(2));
        assertEquals(List.of("h2", "h3"), s.replay().stream().map(LogEvent::message).toList());
        assertFalse(s.gap());
        assertEquals(log.lastSeq(), s.seq());
        assertTrue(subscribe(LogDistributor.Resume.history(LogDistributor.Resume.ALL)).replay().size() >= 3);
        assertTrue(subscribe(LogDistributor.Resume.history(0)).replay().isEmpty());
    }

    @Test
    public void resumeReplaysExactlyTheMissedEvents() throws Exception {
        LogEvent seen = publish("seen");
        publish("missed-1");
        publish("missed-1"); // identical lines are distinct events (v1 de-duplicated them by text)
        LogDistributor.Subscription s = subscribe(LogDistributor.Resume.after(log.epoch(), seen.seq()));
        assertEquals(List.of("missed-1", "missed-1"), s.replay().stream().map(LogEvent::message).toList());
        assertFalse(s.gap());
        assertTrue(subscribe(LogDistributor.Resume.after(log.epoch(), log.lastSeq())).replay().isEmpty());
    }

    @Test
    public void resumeBeyondTheRingReportsAGap() throws Exception {
        LogEvent old = publish("old");
        for (int i = 0; i < LogDistributor.RING_SIZE + 5; i++) publish("filler " + i);
        LogDistributor.Subscription s = subscribe(LogDistributor.Resume.after(log.epoch(), old.seq()));
        assertTrue(s.gap());
        assertEquals(LogDistributor.RING_SIZE, s.replay().size());
    }

    @Test
    public void anotherEpochReplaysEverythingBuffered() throws Exception {
        publish("after restart");
        LogDistributor.Subscription s = subscribe(LogDistributor.Resume.after("00000000", 5));
        assertFalse(s.replay().isEmpty());
        assertEquals("after restart", s.replay().get(s.replay().size() - 1).message());
    }

    @Test
    public void liveEventsFollowTheReplayWithoutDuplicatesUnderConcurrency() throws Exception {
        // Regression: registering before taking the history snapshot duplicated and reordered lines.
        CountDownLatch go = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try { go.await(); } catch (InterruptedException ignored) {}
            for (int i = 0; i < 400; i++) publish("concurrent " + i);
        });
        writer.start();
        go.countDown();
        Thread.sleep(1);
        LogDistributor.Subscription s = subscribe(LogDistributor.Resume.history(LogDistributor.Resume.ALL));
        writer.join();
        List<Long> seqs = new ArrayList<>(s.replay().stream().map(LogEvent::seq).toList());
        Object item;
        while ((item = s.connection().next(200, TimeUnit.MILLISECONDS)) != null) {
            if (item instanceof LogEvent e) seqs.add(e.seq());
        }
        Set<Long> unique = new HashSet<>(seqs);
        assertEquals(seqs.size(), unique.size(), "duplicate events");
        for (int i = 1; i < seqs.size(); i++) assertTrue(seqs.get(i) > seqs.get(i - 1), "out of order at " + i);
        assertEquals(log.lastSeq(), seqs.get(seqs.size() - 1));
    }

    @Test
    public void aClientThatFallsBehindIsDisconnectedNotThinned() throws Exception {
        LogDistributor.Subscription s = subscribe(LogDistributor.Resume.history(0));
        for (int i = 0; i <= StreamConnection.QUEUE_SIZE; i++) publish("flood " + i);
        assertFalse(s.connection().isActive());
        assertTrue(s.connection().overflowed());
        assertNull(s.connection().next(10, TimeUnit.MILLISECONDS));
    }

    @Test
    public void liveDeliveryKeepsOrder() throws Exception {
        LogDistributor.Subscription s = subscribe(LogDistributor.Resume.history(0));
        publish("a");
        publish("b");
        assertEquals(List.of("a", "b"), drain(s.connection(), 2));
    }
}
