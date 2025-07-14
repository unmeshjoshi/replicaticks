package replicated.network;

import replicated.messaging.NetworkAddress;

import java.util.concurrent.atomic.AtomicLong;

// === Per-connection metrics ===
public final class ConnectionStats {
    public final NetworkAddress local;
    public final NetworkAddress remote;
    public final boolean inbound;
    private final long openedAtNanos;
    private volatile long lastActivityNanos;
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private volatile long closedAtNanos = -1;

    ConnectionStats(NetworkAddress local, NetworkAddress remote, boolean inbound) {
        this.local = local;
        this.remote = remote;
        this.inbound = inbound;
        this.openedAtNanos = System.nanoTime();
        this.lastActivityNanos = this.openedAtNanos;
    }

    /**
     * Constructor for outbound connections where the local address is ephemeral.
     * Uses a placeholder for the local address since it's not meaningful for the application.
     */
    static ConnectionStats forOutbound(NetworkAddress remote) {
        NetworkAddress ephemeralLocal = new NetworkAddress("ephemeral", 1);
        return new ConnectionStats(ephemeralLocal, remote, false);
    }

    void recordSent(long n) {
        bytesSent.addAndGet(n);
        lastActivityNanos = System.nanoTime();
    }

    void recordRecv(long n) {
        bytesReceived.addAndGet(n);
        lastActivityNanos = System.nanoTime();
    }

    void markClosed() {
        closedAtNanos = System.nanoTime();
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    public long bytesReceived() {
        return bytesReceived.get();
    }

    public long openedMillis() {
        return openedAtNanos / 1_000_000;
    }

    public long lastActivityMillis() {
        return lastActivityNanos / 1_000_000;
    }

    public long closedMillis() {
        return closedAtNanos < 0 ? -1 : closedAtNanos / 1_000_000;
    }

    public long lifetimeMillis() {
        long end = closedAtNanos < 0 ? System.nanoTime() : closedAtNanos;
        return (end - openedAtNanos) / 1_000_000;
    }

    @Override
    public String toString() {
        return String.format("Conn[%s -> %s, inbound=%s, sent=%d, recv=%d, openMs=%d, lifeMs=%d]", local, remote, inbound, bytesSent(), bytesReceived(), openedMillis(), lifetimeMillis());
    }
}
