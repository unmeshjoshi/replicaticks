package replicated.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.NetworkAddress;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {
    
    private MetricsCollector registry;
    
    @BeforeEach
    void setUp() {
        registry = new MetricsCollector();
    }
    
    @Test
    void shouldStartWithZeroCounts() {
        Metrics metrics = registry.snapshot();
        
        assertEquals(0, metrics.inboundConnections());
        assertEquals(0, metrics.outboundConnections());
        assertEquals(0, metrics.closedConnections());
        assertTrue(metrics.connections().isEmpty());
    }
    
    @Test
    void shouldIncrementConnectionCounts() {
        registry.incrementInboundConnection();
        registry.incrementOutboundConnection();
        registry.incrementClosedConnection();
        
        Metrics metrics = registry.snapshot();
        
        assertEquals(1, metrics.inboundConnections());
        assertEquals(1, metrics.outboundConnections());
        assertEquals(1, metrics.closedConnections());
    }
    
    @Test
    void shouldTrackMultipleIncrements() {
        registry.incrementInboundConnection();
        registry.incrementInboundConnection();
        registry.incrementOutboundConnection();
        
        Metrics metrics = registry.snapshot();
        
        assertEquals(2, metrics.inboundConnections());
        assertEquals(1, metrics.outboundConnections());
        assertEquals(0, metrics.closedConnections());
    }
    
    @Test
    void shouldRegisterAndTrackConnections() {
        NetworkAddress local = new NetworkAddress("127.0.0.1", 8080);
        NetworkAddress remote = new NetworkAddress("127.0.0.2", 9090);
        
        ConnectionStats inboundStats = new ConnectionStats(local, remote, true);
        ConnectionStats outboundStats = new ConnectionStats(local, remote, false);
        
        registry.registerConnection("conn1", inboundStats);
        registry.registerConnection("conn2", outboundStats);
        
        Metrics metrics = registry.snapshot();
        
        assertEquals(2, metrics.connections().size());
        assertTrue(metrics.connections().contains(inboundStats));
        assertTrue(metrics.connections().contains(outboundStats));
    }
    
    @Test
    void shouldUnregisterConnections() {
        NetworkAddress local = new NetworkAddress("127.0.0.1", 8080);
        NetworkAddress remote = new NetworkAddress("127.0.0.2", 9090);
        
        ConnectionStats stats = new ConnectionStats(local, remote, true);
        registry.registerConnection("conn1", stats);
        
        assertEquals(1, registry.snapshot().connections().size());
        
        registry.unregisterConnection("conn1");
        
        assertEquals(0, registry.snapshot().connections().size());
    }
    
    @Test
    void shouldGetConnectionStats() {
        NetworkAddress local = new NetworkAddress("127.0.0.1", 8080);
        NetworkAddress remote = new NetworkAddress("127.0.0.2", 9090);
        
        ConnectionStats stats = new ConnectionStats(local, remote, true);
        registry.registerConnection("conn1", stats);
        
        ConnectionStats retrieved = registry.getConnectionStats("conn1");
        assertEquals(stats, retrieved);
        
        assertNull(registry.getConnectionStats("nonexistent"));
    }
    
    @Test
    void shouldResetAllMetrics() {
        registry.incrementInboundConnection();
        registry.incrementOutboundConnection();
        registry.incrementClosedConnection();
        
        NetworkAddress local = new NetworkAddress("127.0.0.1", 8080);
        NetworkAddress remote = new NetworkAddress("127.0.0.2", 9090);
        ConnectionStats stats = new ConnectionStats(local, remote, true);
        registry.registerConnection("conn1", stats);
        
        registry.reset();
        
        Metrics metrics = registry.snapshot();
        assertEquals(0, metrics.inboundConnections());
        assertEquals(0, metrics.outboundConnections());
        assertEquals(0, metrics.closedConnections());
        assertTrue(metrics.connections().isEmpty());
    }
    
    @Test
    void shouldCreateImmutableSnapshot() {
        registry.incrementInboundConnection();
        
        Metrics snapshot1 = registry.snapshot();
        Metrics snapshot2 = registry.snapshot();
        
        // Snapshots should be independent
        registry.incrementInboundConnection();
        
        assertEquals(1, snapshot1.inboundConnections());
        assertEquals(1, snapshot2.inboundConnections());
    }
} 