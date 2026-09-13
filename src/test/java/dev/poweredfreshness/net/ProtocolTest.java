package dev.poweredfreshness.net;

public final class ProtocolTest {
    public static void main(String[] args) {
        require(Protocol.integer(-2147483648d, Integer.MIN_VALUE, Integer.MAX_VALUE), "signed ID minimum");
        require(!Protocol.integer(2147483648d, Integer.MIN_VALUE, Integer.MAX_VALUE), "ID overflow");
        require(!Protocol.integer(1.5d, Integer.MIN_VALUE, Integer.MAX_VALUE), "fractional ID");
        require(!Protocol.age(Double.NaN), "NaN age");
        require(!Protocol.age(Double.POSITIVE_INFINITY), "infinite age");
        require(!Protocol.age(-1d), "negative age");
        require(Protocol.age(0d), "zero age");
        Protocol order = new Protocol();
        order.begin("nonce-a");
        require(!order.handshake("wrong", "epoch-a"), "unsolicited handshake");
        require(order.handshake("nonce-a", "epoch-a"), "handshake");
        require(order.accept("epoch-a", -4, 2), "first snapshot");
        require(!order.accept("epoch-a", -4, 2), "duplicate");
        require(!order.accept("epoch-a", -4, 1), "stale");
        require(!order.accept("epoch-b", -4, 3), "foreign epoch");
        require(order.accept("epoch-a", 5, 1), "independent item");
        order.begin("nonce-b");
        require(!order.accept("epoch-a", -4, 9), "reconnect rejects old epoch before ack");
        require(!order.handshake("nonce-a", "epoch-a"), "old ack");
        require(order.handshake("nonce-b", "epoch-b"), "new ack");
        require(order.accept("epoch-b", -4, 1), "new epoch resets sequence");
        require(!order.handshake("nonce-b", "epoch-a"), "epoch cannot be replaced after ack");
        System.out.println("ProtocolTest: PASS");
    }
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
