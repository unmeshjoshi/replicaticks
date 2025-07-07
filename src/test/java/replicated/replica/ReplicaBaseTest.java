package replicated.replica;

import replicated.messaging.*;
import replicated.network.SimulatedNetwork;
import replicated.network.MessageContext;
import replicated.storage.*;
import replicated.util.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Random;

/**
 * Tests for the ReplicaBase class common building blocks.
 */
class ReplicaBaseTest {
    
    private NetworkAddress address1;
    private NetworkAddress address2;
    private List<NetworkAddress> peers;
    private MessageBus messageBus;
    private Storage storage;
    
    @BeforeEach
    void setUp() {
        address1 = new NetworkAddress("192.168.1.1", 8080);
        address2 = new NetworkAddress("192.168.1.2", 8080);
        peers = List.of(address2);
        messageBus = new MessageBus(new SimulatedNetwork(new Random(42)), new JsonMessageCodec());
        storage = new SimulatedStorage(new Random(42));
    }
    
    @Test
    void shouldCreateReplicaBaseWithValidParameters() {
        // Given/When
        TestableReplica replica = new TestableReplica("test", address1, peers, messageBus, storage, 10);
        
        // Then
        assertEquals("test", replica.getName());
        assertEquals(address1, replica.getNetworkAddress());
        assertEquals(peers, replica.getPeers());
    }
    
    @Test
    void shouldThrowExceptionForNullName() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica(null, address1, peers, messageBus, storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForNullNetworkAddress() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", null, peers, messageBus, storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForNullPeers() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", address1, null, messageBus, storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionWhenMessageBusWithoutStorage() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", address1, peers, messageBus, null, 10)
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
        TestableReplica replica = new TestableReplica("test", address1, peers, messageBus, storage, 10);
        
        // When
        String id1 = replica.testGenerateRequestId();
        String id2 = replica.testGenerateRequestId();
        
        // Then
        assertNotEquals(id1, id2);
        assertTrue(id1.startsWith("test-"));
        assertTrue(id2.startsWith("test-"));
    }

    @Test
    void shouldHandleTimeoutsCorrectly() {
        // Given
        TestableReplica replica = new TestableReplica("test", address1, peers, messageBus, storage, 5);
        TestPendingRequest request = new TestPendingRequest("req-1", address2, "key1", 5); // Use 5 ticks timeout
        replica.testAddPendingRequest("req-1", request);
        
        // When - tick beyond timeout (timeout=5, so tick=6 should timeout)
        for (int i = 0; i < 6; i++) {
            replica.tick();
        }
        
        // Then
        assertTrue(replica.timeoutResponseSent);
        assertEquals(request, replica.lastTimeoutRequest);
    }
    
    @Test
    void shouldNotTimeoutRequestsWithinTimeout() {
        // Given
        TestableReplica replica = new TestableReplica("test", address1, peers, messageBus, storage, 10);
        TestPendingRequest request = new TestPendingRequest("req-1", address2, "key1", 10);
        replica.testAddPendingRequest("req-1", request);
        
        // When - tick within timeout (timeout=10, so tick=5 should not timeout)
        for (int i = 0; i < 5; i++) {
            replica.tick();
        }
        
        // Then
        assertFalse(replica.timeoutResponseSent);
        assertNull(replica.lastTimeoutRequest);
    }
    
    @Test
    void shouldImplementEqualsAndHashCodeBasedOnNameAndAddress() {
        // Given
        TestableReplica replica1 = new TestableReplica("test", address1, peers, messageBus, storage, 10);
        TestableReplica replica2 = new TestableReplica("test", address1, List.of(), messageBus, storage, 10);
        TestableReplica replica3 = new TestableReplica("different", address1, peers, messageBus, storage, 10);
        
        // Then
        assertEquals(replica1, replica2); // Same name and address, different peers
        assertNotEquals(replica1, replica3); // Different name
        assertEquals(replica1.hashCode(), replica2.hashCode());
    }
    
    @Test
    void shouldCreateDefensiveCopyOfPeers() {
        // Given
        List<NetworkAddress> mutablePeers = new java.util.ArrayList<>(peers);
        TestableReplica replica = new TestableReplica("test", address1, mutablePeers, messageBus, storage, 10);
        
        // When
        mutablePeers.clear();
        
        // Then
        assertEquals(1, replica.getPeers().size()); // Should not be affected by external changes
    }
    
    // Test implementations
    
    private static class TestableReplica extends Replica {
        boolean timeoutResponseSent = false;
        PendingRequest lastTimeoutRequest = null;
        
        TestableReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                       MessageBus messageBus, Storage storage, int requestTimeoutTicks) {
            super(name, networkAddress, peers, messageBus, storage, requestTimeoutTicks);
        }
        
        @Override
        public void onMessageReceived(Message message, MessageContext ctx) { }
        
        @Override
        protected void sendTimeoutResponse(PendingRequest request) {
            timeoutResponseSent = true;
            lastTimeoutRequest = request;
        }
        
        // Test helper methods
        String testGenerateRequestId() {
            return generateRequestId();
        }
        
        void testAddPendingRequest(String id, PendingRequest request) {
            pendingRequests.put(id, request);
        }
    }
    
    private static class TestPendingRequest extends Replica.PendingRequest {
        TestPendingRequest(String requestId, NetworkAddress clientAddress, String key, int timeoutTicks) {
            super(requestId, clientAddress, key, createTimeout(requestId, timeoutTicks));
        }
        
        private static Timeout createTimeout(String requestId, int timeoutTicks) {
            Timeout timeout = new Timeout("test-request-" + requestId, timeoutTicks);
            timeout.start();
            return timeout;
        }
    }
    
    private static class TestableStorage implements Storage {
        boolean tickCalled = false;
        
        @Override
        public replicated.future.ListenableFuture<replicated.storage.VersionedValue> get(byte[] key) {
            return null; // Not needed for this test
        }
        
        @Override
        public replicated.future.ListenableFuture<Boolean> set(byte[] key, replicated.storage.VersionedValue value) {
            return null; // Not needed for this test
        }
        
        @Override
        public void tick() {
            tickCalled = true;
        }
    }
} 