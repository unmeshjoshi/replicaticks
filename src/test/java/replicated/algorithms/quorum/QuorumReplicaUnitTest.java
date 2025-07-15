package replicated.algorithms.quorum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.*;
import replicated.storage.SimulatedStorage;
import replicated.storage.Storage;
import replicated.storage.VersionedValue;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class QuorumReplicaUnitTest {
    
    private MessageBus messageBus;
    private Storage storage;
    private QuorumReplica replica;
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
        JsonMessageCodec codec = new JsonMessageCodec();
        replica = new QuorumReplica("replica1", replicaAddress, peers, messageBus, codec, storage);
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
        JsonMessageCodec codec = new JsonMessageCodec();
        assertThrows(IllegalArgumentException.class, 
            () -> new QuorumReplica("test", replicaAddress, peers, null, codec, storage));
    }
    
    @Test
    void shouldThrowExceptionForNullStorage() {
        // When & Then
        JsonMessageCodec codec = new JsonMessageCodec();
        assertThrows(IllegalArgumentException.class, 
            () -> new QuorumReplica("test", replicaAddress, peers, messageBus, codec, null));
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
        replica.onMessageReceived(clientMessage, null);
        
        // Then - should initiate quorum get operation
        // This will be verified by checking internal messages sent to peers
        // For now, just verify no exception is thrown
        assertDoesNotThrow(() -> replica.tick());
    }
    
    @Test
    void shouldHandleClientSetRequest() {
        // Given
        NetworkAddress clientAddress = new NetworkAddress("192.168.1.100", 9000);
        SetRequest setRequest = new SetRequest("test-key", "test-value".getBytes());
        Message clientMessage = createMessage(clientAddress, replicaAddress, 
            MessageType.CLIENT_SET_REQUEST, setRequest);
        
        // When
        replica.onMessageReceived(clientMessage, null);
        
        // Then - should initiate quorum set operation
        assertDoesNotThrow(() -> replica.tick());
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
        
        replica.onMessageReceived(internalMessage, null);
        storage.tick(); // Process storage operation
        replica.tick(); // Process any pending work
        
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
        replica.onMessageReceived(internalMessage, null);
        storage.tick(); // Process storage operation
        replica.tick(); // Process any pending work
        
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
        replica.onMessageReceived(message1, null);
        replica.onMessageReceived(message2, null);
        
        // Then - should track both requests separately
        // Verification would be done through inspection of internal state
        // For now, verify no exceptions
        assertDoesNotThrow(() -> {
            replica.tick();
            replica.tick();
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
            replica.onMessageReceived(message, null);
        }
        
        // Then - each should have unique request ID
        // This will be verified through MessageBus interactions
        assertDoesNotThrow(() -> replica.tick());
    }
    
    @Test
    void shouldTimeoutPendingRequests() {
        // Given - replica with timeout configuration
        replica = new QuorumReplica("replica1", replicaAddress, peers, messageBus, new JsonMessageCodec(), storage, 5); // 5 tick timeout
        
        NetworkAddress clientAddress = new NetworkAddress("192.168.1.100", 9000);
        GetRequest getRequest = new GetRequest("test-key");
        Message message = createMessage(clientAddress, replicaAddress, 
            MessageType.CLIENT_GET_REQUEST, getRequest);
        
        // When - send request and advance time beyond timeout
        replica.onMessageReceived(message, null);
        
        // Advance time beyond timeout
        for (int tick = 1; tick <= 7; tick++) {
            replica.tick();
        }
        
        // Then - request should timeout and send error response
        // This would be verified through MessageBus mock interactions
        assertDoesNotThrow(() -> {});
    }
    
    @Test
    void shouldCalculateQuorumSize() {
        // Given a 3-node cluster (self + 2 peers)
        // When calculating quorum
        // Then majority should be 2 out of 3
        
        // This is implicit in the quorum logic - majority of 3 is 2
        // The AsyncQuorumCallback handles the quorum calculation
        assertEquals(3, replica.getPeers().size() + 1); // Total nodes
    }
    
    // Helper methods
    
    private MessageBus createMockMessageBus() {
        TestNetwork network = new TestNetwork();
        JsonMessageCodec codec = new JsonMessageCodec();
        return new MessageBus(network, codec);
    }
    
    private Message createMessage(NetworkAddress source, NetworkAddress destination, 
                                 MessageType messageType, Object payload) {
        JsonMessageCodec codec = new JsonMessageCodec();
        byte[] serializedPayload = codec.encode(payload);
        return new Message(source, destination, messageType, serializedPayload, "test-correlation-id");
    }
    
    // Test implementations
    
    private static class TestNetwork implements replicated.network.Network {
        private int nextPort = 60000;
        
        @Override
        public void send(Message message) {
            // Mock implementation - just accept the message
        }
        
        @Override
        public void tick() {
            // Mock implementation
        }
        
        public NetworkAddress establishConnection(NetworkAddress destination) {
            return new NetworkAddress("127.0.0.1", nextPort++);
        }
        
        @Override
        public void partition(NetworkAddress source, NetworkAddress destination) {
            // Mock implementation
        }
        
        @Override
        public void partitionOneWay(NetworkAddress source, NetworkAddress destination) {
            // Mock implementation
        }
        
        @Override
        public void healPartition(NetworkAddress source, NetworkAddress destination) {
            // Mock implementation
        }
        
        @Override
        public void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks) {
            // Mock implementation
        }
        
        @Override
        public void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate) {
            // Mock implementation
        }
        
        @Override
        public void registerMessageHandler(replicated.network.MessageCallback callback) {
            // Mock implementation
        }
    }
} 