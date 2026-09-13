package dev.poweredfreshness.net;

import java.util.Map;
import java.util.TreeMap;

/** Bounded latest-value queue, retaining IDs nearest the rotating delivery cursor. */
final class FairOutbox<T> {
    private final int capacity;
    private final TreeMap<Integer, T> items = new TreeMap<>();
    private int cursor = Integer.MAX_VALUE;

    FairOutbox(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    void offer(int id, T item) {
        if (item == null) throw new IllegalArgumentException("item");
        if (items.containsKey(id) || items.size() < capacity) {
            items.put(id, item);
            return;
        }
        Map.Entry<Integer, T> last = items.floorEntry(cursor);
        if (last == null) last = items.lastEntry();
        if (distance(id) < distance(last.getKey())) {
            items.remove(last.getKey());
            items.put(id, item);
        }
    }

    T poll() {
        if (items.isEmpty()) return null;
        Map.Entry<Integer, T> next = items.higherEntry(cursor);
        if (next == null) next = items.firstEntry();
        cursor = next.getKey();
        return items.remove(cursor);
    }

    void clear() { items.clear(); cursor = Integer.MAX_VALUE; }

    private long distance(int id) {
        long distance = ((long) id - cursor) & 0xffffffffL;
        return distance == 0 ? 0x100000000L : distance;
    }
}
