package replicated.replica;

import replicated.messaging.NetworkAddress;
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
        Replica replica = new Replica(name, networkAddress, peers);
        
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
            new Replica(null, networkAddress, peers));
    }
    
    @Test
    void shouldRejectNullNetworkAddress() {
        // Given
        String name = "replica-1";
        List<NetworkAddress> peers = List.of();
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new Replica(name, null, peers));
    }
    
    @Test
    void shouldRejectNullPeers() {
        // Given
        String name = "replica-1";
        NetworkAddress networkAddress = new NetworkAddress("192.168.1.10", 8080);
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new Replica(name, networkAddress, null));
    }
    
    @Test
    void shouldAllowEmptyPeersList() {
        // Given
        String name = "solo-replica";
        NetworkAddress networkAddress = new NetworkAddress("192.168.1.10", 8080);
        List<NetworkAddress> emptyPeers = List.of();
        
        // When
        Replica replica = new Replica(name, networkAddress, emptyPeers);
        
        // Then
        assertTrue(replica.getPeers().isEmpty());
    }
    
    @Test
    void shouldHaveTickMethodForSimulation() {
        // Given
        Replica replica = createTestReplica();
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
        
        Replica replica1 = new Replica(name, address, peers1);
        Replica replica2 = new Replica(name, address, peers2); // same name/address, different peers
        Replica replica3 = new Replica("different-name", address, peers1);
        
        // When & Then
        assertEquals(replica1, replica2); // Equal based on name + address only
        assertNotEquals(replica1, replica3);
        assertEquals(replica1.hashCode(), replica2.hashCode());
    }
    
    private Replica createTestReplica() {
        return new Replica(
            "test-replica",
            new NetworkAddress("127.0.0.1", 8080),
            List.of(new NetworkAddress("127.0.0.1", 8081))
        );
    }
} 