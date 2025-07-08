package replicated.replica;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.network.SimulatedNetwork;
import replicated.storage.SimulatedStorage;
import replicated.storage.Storage;
import replicated.util.Timeout;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ReplicaBase class common building blocks.
 */
class ReplicaBaseTest {
    
    private NetworkAddress address1;
    private NetworkAddress address2;
    private List<NetworkAddress> peers;
    private ServerMessageBus serverBus;
    private Storage storage;
    
    @BeforeEach
    void setUp() {
        address1 = new NetworkAddress("192.168.1.1", 8080);
        address2 = new NetworkAddress("192.168.1.2", 8080);
        peers = List.of(address2);
        serverBus = new ServerMessageBus(new SimulatedNetwork(new Random(42)), new JsonMessageCodec());
        storage = new SimulatedStorage(new Random(42));
    }
    
    @Test
    void shouldCreateReplicaBaseWithValidParameters() {
        // Given/When
        TestableReplica replica = new TestableReplica("test", address1, peers, serverBus, storage, 10);
        
        // Then
        assertEquals("test", replica.getName());
        assertEquals(address1, replica.getNetworkAddress());
        assertEquals(peers, replica.getPeers());
    }
    
    @Test
    void shouldThrowExceptionForNullName() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica(null, address1, peers, serverBus, storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForNullNetworkAddress() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", null, peers, serverBus, storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForNullPeers() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", address1, null, serverBus, storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionWhenMessageBusWithoutStorage() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", address1, peers, serverBus, null, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionWhenStorageWithoutMessageBus() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", address1, peers, null, storage, 10)
        );
    }
    
    @Test
    void shouldGenerateUniqueRequestIds() {
        // Given
        TestableReplica replica = new TestableReplica("test", address1, peers, serverBus, storage, 10);
        
        // When
        String id1 = replica.testGenerateRequestId();
        String id2 = replica.testGenerateRequestId();
        
        // Then
        assertNotEquals(id1, id2);
        assertTrue(id1.startsWith("test-"));
        assertTrue(id2.startsWith("test-"));
    }

    @Test
    void shouldImplementEqualsAndHashCodeBasedOnNameAndAddress() {
        // Given
        TestableReplica replica1 = new TestableReplica("test", address1, peers, serverBus, storage, 10);
        TestableReplica replica2 = new TestableReplica("test", address1, List.of(), serverBus, storage, 10);
        TestableReplica replica3 = new TestableReplica("different", address1, peers, serverBus, storage, 10);
        
        // Then
        assertEquals(replica1, replica2); // Same name and address, different peers
        assertNotEquals(replica1, replica3); // Different name
        assertEquals(replica1.hashCode(), replica2.hashCode());
    }
    
    @Test
    void shouldCreateDefensiveCopyOfPeers() {
        // Given
        List<NetworkAddress> mutablePeers = new java.util.ArrayList<>(peers);
        TestableReplica replica = new TestableReplica("test", address1, mutablePeers, serverBus, storage, 10);
        
        // When
        mutablePeers.clear();
        
        // Then
        assertEquals(1, replica.getPeers().size()); // Should not be affected by external changes
    }

    // Test implementations

    private static class TestableReplica extends Replica {
        boolean timeoutResponseSent = false;

        TestableReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                       BaseMessageBus messageBus, Storage storage, int requestTimeoutTicks) {
            super(name, networkAddress, peers, messageBus, storage, requestTimeoutTicks);
        }

        @Override
        public void onMessageReceived(Message message, MessageContext ctx) { }

        // Test helper methods
        String testGenerateRequestId() {
            return generateRequestId();
        }
    }


}