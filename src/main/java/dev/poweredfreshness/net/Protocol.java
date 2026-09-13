package dev.poweredfreshness.net;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure, bounded validation and per-item replay protection for protocol v1. */
final class Protocol {
    private String nonce;
    private String epoch;
    private final Map<Integer, Long> sequences = new LinkedHashMap<>();

    static boolean integer(Object value, long min, long max) {
        if (!(value instanceof Number number)) return false;
        double d = number.doubleValue();
        return Double.isFinite(d) && d == Math.rint(d) && d >= min && d <= max;
    }

    static boolean age(Object value) {
        if (!(value instanceof Number number)) return false;
        double d = number.doubleValue();
        return Double.isFinite(d) && d >= 0 && d <= Float.MAX_VALUE;
    }

    void begin(String value) {
        nonce = value;
        epoch = null;
        sequences.clear();
    }

    boolean handshake(String value, String newEpoch) {
        if (epoch != null || nonce == null || !nonce.equals(value)
                || newEpoch == null || newEpoch.isEmpty() || newEpoch.length() > 64) return false;
        epoch = newEpoch;
        return true;
    }

    boolean ready() { return epoch != null; }

    boolean accept(String value, int id, long sequence) {
        if (epoch == null || !epoch.equals(value) || sequence < 1 || sequence > 9007199254740991L) return false;
        Long previous = sequences.get(id);
        if (previous != null && sequence <= previous) return false;
        // Fail closed rather than evicting replay protection and accepting old packets.
        if (previous == null && sequences.size() >= 65536) return false;
        sequences.put(id, sequence);
        return true;
    }
}
