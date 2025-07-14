package replicated.network;

import replicated.messaging.NetworkAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Collector for collecting and managing network metrics.
 * Thread-safe collection of metrics that can be snapshotted.
 */
public final class MetricsCollector {
    private final AtomicInteger inboundConnectionCount = new AtomicInteger();
    private final AtomicInteger outboundConnectionCount = new AtomicInteger();
    private final AtomicInteger closedConnectionCount = new AtomicInteger();
    
    private final ConcurrentHashMap<String, ConnectionStats> activeConnections = new ConcurrentHashMap<>();
    
    public void incrementInboundConnection() {
        inboundConnectionCount.incrementAndGet();
    }
    
    public void incrementOutboundConnection() {
        outboundConnectionCount.incrementAndGet();
    }
    
    public void incrementClosedConnection() {
        closedConnectionCount.incrementAndGet();
    }
    
    public void registerConnection(String connectionId, ConnectionStats stats) {
        activeConnections.put(connectionId, stats);
    }
    
    public void unregisterConnection(String connectionId) {
        activeConnections.remove(connectionId);
    }
    
    public ConnectionStats getConnectionStats(String connectionId) {
        return activeConnections.get(connectionId);
    }
    
    /**
     * Records bytes sent for a connection.
     */
    public void recordSent(String connectionId, int bytesSent) {
        ConnectionStats stats = activeConnections.get(connectionId);
        if (stats != null) {
            stats.recordSent(bytesSent);
        }
    }
    
    /**
     * Records bytes received for a connection.
     */
    public void recordReceived(String connectionId, int bytesReceived) {
        ConnectionStats stats = activeConnections.get(connectionId);
        if (stats != null) {
            stats.recordRecv(bytesReceived);
        }
    }
    
    /**
     * Marks a connection as closed.
     */
    public void markConnectionClosed(String connectionId) {
        ConnectionStats stats = activeConnections.get(connectionId);
        if (stats != null) {
            stats.markClosed();
        }
    }

    /**
     * Registers an inbound connection with metrics tracking.
     * Increments the inbound connection counter and creates ConnectionStats with both local and remote addresses.
     */
    public void registerInboundConnection(String connectionId, NetworkAddress localAddress, NetworkAddress remoteAddress) {
        incrementInboundConnection();
        ConnectionStats stats = new ConnectionStats(localAddress, remoteAddress, true);
        registerConnection(connectionId, stats);
    }

    /**
     * Registers an inbound connection with metrics tracking, extracting local address from the channel.
     * This method handles the complete inbound connection registration including address extraction.
     */
    public void registerInboundConnection(String connectionId, java.nio.channels.SocketChannel channel, NetworkAddress remoteAddress) throws java.io.IOException {
        java.net.InetSocketAddress localSocket = (java.net.InetSocketAddress) channel.getLocalAddress();
        NetworkAddress localAddress = NetworkAddress.from(localSocket);
        registerInboundConnection(connectionId, localAddress, remoteAddress);
    }

    /**
     * Registers an outbound connection with metrics tracking.
     * Increments the outbound connection counter and creates ConnectionStats using the factory method.
     */
    public void registerOutboundConnection(String connectionId, NetworkAddress remoteAddress) {
        incrementOutboundConnection();
        ConnectionStats stats = ConnectionStats.forOutbound(remoteAddress);
        registerConnection(connectionId, stats);
    }

    /**
     * Records bytes sent for a connection and updates activity.
     */
    public void recordBytesSent(String connectionId, int bytesSent) {
        recordSent(connectionId, bytesSent);
    }

    /**
     * Records bytes received for a connection and updates activity.
     */
    public void recordBytesReceived(String connectionId, int bytesReceived) {
        recordReceived(connectionId, bytesReceived);
    }

    /**
     * Handles complete connection cleanup including marking as closed, unregistering, and incrementing closed counter.
     */
    public void cleanupConnection(String connectionId) {
        markConnectionClosed(connectionId);
        unregisterConnection(connectionId);
        incrementClosedConnection();
    }
    
    /**
     * Creates an immutable snapshot of current metrics.
     */
    public Metrics snapshot() {
        List<ConnectionStats> connections = activeConnections.values()
            .stream()
            .collect(Collectors.toList());
            
        return new Metrics(
            inboundConnectionCount.get(),
            outboundConnectionCount.get(), 
            closedConnectionCount.get(),
            connections
        );
    }
    
    /**
     * Resets all counters to zero. Useful for testing.
     */
    public void reset() {
        inboundConnectionCount.set(0);
        outboundConnectionCount.set(0);
        closedConnectionCount.set(0);
        activeConnections.clear();
    }
} 