package dev.poweredfreshness.net;

import java.util.HashSet;
import java.util.Set;

public final class FairOutboxTest {
    public static void main(String[] args) {
        FairOutbox<Integer> queue = new FairOutbox<>(4);
        Set<Integer> seen = new HashSet<>();
        // Every production pass visits the same oversized inventory in the same order.
        // A first-come drop-tail queue would permanently starve IDs 4..99.
        for (int cycle = 0; cycle < 50; cycle++) {
            for (int id = 0; id < 100; id++) queue.offer(id, id);
            for (int send = 0; send < 2; send++) seen.add(queue.poll());
        }
        if (seen.size() != 100) throw new AssertionError("starved IDs: delivered " + seen.size());
        queue.clear();
        queue.offer(Integer.MIN_VALUE, 1);
        queue.offer(Integer.MIN_VALUE, 2);
        if (queue.poll() != 2 || queue.poll() != null) throw new AssertionError("coalescing latest value");
        queue.offer(Integer.MAX_VALUE, 3);
        queue.offer(-1, 4);
        if (queue.poll() != 4 || queue.poll() != 3) throw new AssertionError("signed-ID traversal");
        if (queue.poll() != null) throw new AssertionError("empty queue");
        System.out.println("FairOutboxTest: PASS (all 100 fixed-order IDs sent through capacity 4)");
    }
}
