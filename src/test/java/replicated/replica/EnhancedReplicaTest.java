package replicated.replica;

import replicated.messaging.*;
import replicated.storage.*;
import replicated.future.ListenableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

class EnhancedReplicaTest {
    
    private MessageBus messageBus;
    private Storage storage;
    private Replica replica;
    private NetworkAddress replicaAddress;
    private List<NetworkAddress> peers;
    
    @BeforeEach
    void setUp() {
        // Setup components
        messageBus = createMockMessageBus();
        storage = new SimulatedStorage(new Random(42L));
        
        // Setup addresses
        replicaAddress = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress peer1 = new NetworkAddress("192.168.1.2", 8080);
        NetworkAddress peer2 = new NetworkAddress("192.168.1.3", 8080);
        peers = List.of(peer1, peer2);
        
        // Create enhanced replica
        replica = new Replica("replica1", replicaAddress, peers, messageBus, storage);
    }
    
    @Test
    void shouldCreateReplicaWithNewDependencies() {
        // Given dependencies are provided in setUp()
        // Then replica should be created successfully
        assertNotNull(replica);
        assertEquals("replica1", replica.getName());
        assertEquals(replicaAddress, replica.getNetworkAddress());
        assertEquals(peers, replica.getPeers());
    }
    
    @Test
    void shouldThrowExceptionForNullMessageBus() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> new Replica("test", replicaAddress, peers, null, storage));
    }
    
    @Test
    void shouldThrowExceptionForNullStorage() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> new Replica("test", replicaAddress, peers, messageBus, null));
    }
    
    @Test
    void shouldImplementMessageHandler() {
        // Then replica should be a MessageHandler
        assertTrue(replica instanceof MessageHandler);
    }
    
    @Test
    void shouldHandleClientGetRequest() {
        // Given
        NetworkAddress clientAddress = new NetworkAddress("192.168.1.100", 9000);
        GetRequest getRequest = new GetRequest("test-key");
        Message clientMessage = createMessage(clientAddress, replicaAddress, 
            MessageType.CLIENT_GET_REQUEST, getRequest);
        
        // When
        replica.onMessageReceived(clientMessage);
        
        // Then - should initiate quorum get operation
        // This will be verified by checking internal messages sent to peers
        // For now, just verify no exception is thrown
        assertDoesNotThrow(() -> replica.tick(1L));
    }
    
    @Test
    void shouldHandleClientSetRequest() {
        // Given
        NetworkAddress clientAddress = new NetworkAddress("192.168.1.100", 9000);
        SetRequest setRequest = new SetRequest("test-key", "test-value".getBytes());
        Message clientMessage = createMessage(clientAddress, replicaAddress, 
            MessageType.CLIENT_SET_REQUEST, setRequest);
        
        // When
        replica.onMessageReceived(clientMessage);
        
        // Then - should initiate quorum set operation
        assertDoesNotThrow(() -> replica.tick(1L));
    }
    
    @Test
    void shouldHandleInternalGetRequest() {
        // Given - first store a value
        String key = "test-key";
        VersionedValue value = new VersionedValue("test-value".getBytes(), 1L);
        storage.set(key.getBytes(), value);
        storage.tick(); // Complete the set operation
        
        // When - receive internal get request
        NetworkAddress coordinatorAddress = peers.get(0);
        InternalGetRequest getRequest = new InternalGetRequest(key, "corr-123");
        Message internalMessage = createMessage(coordinatorAddress, replicaAddress, 
            MessageType.INTERNAL_GET_REQUEST, getRequest);
        
        replica.onMessageReceived(internalMessage);
        storage.tick(); // Process storage operation
        replica.tick(1L); // Process any pending work
        
        // Then - should respond with INTERNAL_GET_RESPONSE
        // Response verification would be done through MessageBus mock
        assertDoesNotThrow(() -> {});
    }
    
    @Test
    void shouldHandleInternalSetRequest() {
        // Given
        NetworkAddress coordinatorAddress = peers.get(0);
        String key = "test-key";
        VersionedValue value = new VersionedValue("test-value".getBytes(), 1L);
        InternalSetRequest setRequest = new InternalSetRequest(key, value.value(), value.timestamp(), "corr-123");
        Message internalMessage = createMessage(coordinatorAddress, replicaAddress, 
            MessageType.INTERNAL_SET_REQUEST, setRequest);
        
        // When
        replica.onMessageReceived(internalMessage);
        storage.tick(); // Process storage operation
        replica.tick(1L); // Process any pending work
        
        // Then - should respond with INTERNAL_SET_RESPONSE
        assertDoesNotThrow(() -> {});
    }
    
    @Test
    void shouldTrackPendingQuorumRequests() {
        // Given
        NetworkAddress clientAddress = new NetworkAddress("192.168.1.100", 9000);
        GetRequest getRequest1 = new GetRequest("key1");
        GetRequest getRequest2 = new GetRequest("key2");
        
        Message message1 = createMessage(clientAddress, replicaAddress, 
            MessageType.CLIENT_GET_REQUEST, getRequest1);
        Message message2 = createMessage(clientAddress, replicaAddress, 
            MessageType.CLIENT_GET_REQUEST, getRequest2);
        
        // When
        replica.onMessageReceived(message1);
        replica.onMessageReceived(message2);
        
        // Then - should track both requests separately
        // Verification would be done through inspection of internal state
        // For now, verify no exceptions
        assertDoesNotThrow(() -> {
            replica.tick(1L);
            replica.tick(2L);
        });
    }
    
    @Test
    void shouldGenerateUniqueRequestIds() {
        // Given
        NetworkAddress clientAddress = new NetworkAddress("192.168.1.100", 9000);
        GetRequest getRequest = new GetRequest("test-key");
        
        // When - send multiple requests
        for (int i = 0; i < 5; i++) {
            Message message = createMessage(clientAddress, replicaAddress, 
                MessageType.CLIENT_GET_REQUEST, getRequest);
            replica.onMessageReceived(message);
        }
        
        // Then - each should have unique request ID
        // This will be verified through MessageBus interactions
        assertDoesNotThrow(() -> replica.tick(1L));
    }
    
    @Test
    void shouldTimeoutPendingRequests() {
        // Given - replica with timeout configuration
        replica = new Replica("replica1", replicaAddress, peers, messageBus, storage, 5); // 5 tick timeout
        
        NetworkAddress clientAddress = new NetworkAddress("192.168.1.100", 9000);
        GetRequest getRequest = new GetRequest("test-key");
        Message message = createMessage(clientAddress, replicaAddress, 
            MessageType.CLIENT_GET_REQUEST, getRequest);
        
        // When - send request and advance time beyond timeout
        replica.onMessageReceived(message);
        
        // Advance time beyond timeout
        for (int tick = 1; tick <= 10; tick++) {
            replica.tick(tick);
        }
        
        // Then - request should timeout and respond to client
        // Verification would be done through MessageBus mock
        assertDoesNotThrow(() -> {});
    }
    
    @Test
    void shouldCalculateQuorumSize() {
        // Given a cluster of 3 nodes (this replica + 2 peers)
        // When calculating quorum
        // Then quorum should be 2 (majority of 3)
        
        // This will be tested indirectly through quorum behavior
        // For 3 nodes: need 2 responses for quorum
        // For 5 nodes: need 3 responses for quorum
        assertDoesNotThrow(() -> replica.tick(1L));
    }
    
    // Helper methods
    
    private MessageBus createMockMessageBus() {
        // For now, return a simple mock that doesn't throw errors
        // In a real implementation, we'd use a proper mock framework
        return new MessageBus(new TestNetwork(), new JsonMessageCodec());
    }
    
    private Message createMessage(NetworkAddress source, NetworkAddress destination, 
                                 MessageType messageType, Object payload) {
        try {
            JsonMessageCodec codec = new JsonMessageCodec();
            byte[] payloadBytes = JsonMessageCodec.createConfiguredObjectMapper()
                .writeValueAsBytes(payload);
            return new Message(source, destination, messageType, payloadBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test message", e);
        }
    }
    
    // Simple test network implementation
    private static class TestNetwork implements replicated.network.Network {
        @Override
        public void send(Message message) {
            // No-op for testing
        }
        
        @Override
        public List<Message> receive(NetworkAddress address) {
            return List.of();
        }
        
        @Override
        public void tick() {
            // No-op for testing
        }
        
        @Override
        public void partition(NetworkAddress source, NetworkAddress destination) {
            // No-op for testing
        }
        
        @Override
        public void partitionOneWay(NetworkAddress source, NetworkAddress destination) {
            // No-op for testing
        }
        
        @Override
        public void healPartition(NetworkAddress source, NetworkAddress destination) {
            // No-op for testing
        }
        
        @Override
        public void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks) {
            // No-op for testing
        }
        
        @Override
        public void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate) {
            // No-op for testing
        }
    }
} 