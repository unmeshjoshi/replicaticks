package replicated.replica;

import replicated.messaging.NetworkAddress;
import replicated.messaging.Message;
import replicated.network.MessageContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class ReplicaTest {

    @Test
    void shouldCreateReplicaWithBasicProperties() {
        // Given
        String name = "replica-1";
        NetworkAddress networkAddress = new NetworkAddress("192.168.1.10", 8080);
        List<NetworkAddress> peers = List.of(
            new NetworkAddress("192.168.1.11", 8080),
            new NetworkAddress("192.168.1.12", 8080)
        );
        
        // When
        TestableReplica replica = new TestableReplica(name, networkAddress, peers);
        
        // Then
        assertEquals(name, replica.getName());
        assertEquals(networkAddress, replica.getNetworkAddress());
        assertEquals(peers, replica.getPeers());
    }
    
    @Test
    void shouldRejectNullName() {
        // Given
        NetworkAddress networkAddress = new NetworkAddress("192.168.1.10", 8080);
        List<NetworkAddress> peers = List.of();
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new TestableReplica(null, networkAddress, peers));
    }
    
    @Test
    void shouldRejectNullNetworkAddress() {
        // Given
        String name = "replica-1";
        List<NetworkAddress> peers = List.of();
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new TestableReplica(name, null, peers));
    }
    
    @Test
    void shouldRejectNullPeers() {
        // Given
        String name = "replica-1";
        NetworkAddress networkAddress = new NetworkAddress("192.168.1.10", 8080);
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new TestableReplica(name, networkAddress, null));
    }
    
    @Test
    void shouldAllowEmptyPeersList() {
        // Given
        String name = "solo-replica";
        NetworkAddress networkAddress = new NetworkAddress("192.168.1.10", 8080);
        List<NetworkAddress> emptyPeers = List.of();
        
        // When
        TestableReplica replica = new TestableReplica(name, networkAddress, emptyPeers);
        
        // Then
        assertTrue(replica.getPeers().isEmpty());
    }
    
    @Test
    void shouldHaveTickMethodForSimulation() {
        // Given
        TestableReplica replica = createTestReplica();
        long currentTick = 100L;
        
        // When & Then - should not throw
        assertDoesNotThrow(() -> replica.tick(currentTick));
    }
    
    @Test
    void shouldProvideEqualityBasedOnNameAndAddress() {
        // Given
        String name = "replica-1";
        NetworkAddress address = new NetworkAddress("192.168.1.10", 8080);
        List<NetworkAddress> peers1 = List.of(new NetworkAddress("192.168.1.11", 8080));
        List<NetworkAddress> peers2 = List.of(new NetworkAddress("192.168.1.12", 8080));
        
        TestableReplica replica1 = new TestableReplica(name, address, peers1);
        TestableReplica replica2 = new TestableReplica(name, address, peers2); // same name/address, different peers
        TestableReplica replica3 = new TestableReplica("different-name", address, peers1);
        
        // When & Then
        assertEquals(replica1, replica2); // Equal based on name + address only
        assertNotEquals(replica1, replica3);
        assertEquals(replica1.hashCode(), replica2.hashCode());
    }
    
    private TestableReplica createTestReplica() {
        return new TestableReplica(
            "test-replica",
            new NetworkAddress("127.0.0.1", 8080),
            List.of(new NetworkAddress("127.0.0.1", 8081))
        );
    }
    
    // Test implementation of Replica for testing basic functionality
    private static class TestableReplica extends Replica {
        TestableReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers) {
            super(name, networkAddress, peers, null, null, 10);
        }
        
        @Override
        public void onMessageReceived(Message message, MessageContext ctx) { }
        
        @Override
        protected void sendTimeoutResponse(PendingRequest request) {
            // Test implementation - no timeout handling needed
        }
    }
} 