package dev.poweredfreshness.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-item replay protection within an explicitly selected server session.
 * Sequences must come from one session-wide monotonically increasing counter.
 * Under capacity pressure, an eviction watermark conservatively rejects all older
 * snapshots, including late first deliveries. Eviction never reopens accepted replays.
 */
public final class SnapshotOrder {
    private final int capacity;
    private final Map<Integer, Long> latest = new HashMap<>();
    private String session;
    private long retiredThrough = -1;

    public SnapshotOrder(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Select only a session validated by the connection handshake, never a data packet. */
    public synchronized void reset(String session) {
        if (session == null || session.isBlank()) throw new IllegalArgumentException("session must not be blank");
        if (session.equals(this.session)) return;
        this.session = session;
        latest.clear();
        retiredThrough = -1;
    }

    /** Item IDs use the complete signed 32-bit domain. Rejection does not reset sessions. */
    public synchronized boolean accept(String session, long sequence, int itemId) {
        if (this.session == null || !this.session.equals(session) || sequence < 0 || sequence <= retiredThrough) return false;
        Long previous = latest.get(itemId);
        if (previous != null && sequence <= previous) return false;
        if (previous == null && latest.size() >= capacity) {
            long oldest = Long.MAX_VALUE;
            for (long value : latest.values()) oldest = Math.min(oldest, value);
            if (sequence <= oldest) return false;
            retiredThrough = oldest;
            latest.values().removeIf(value -> value <= retiredThrough);
        }
        latest.put(itemId, sequence);
        return true;
    }
}
