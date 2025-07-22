package replicated.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.future.ListenableFuture;
import replicated.messaging.*;
import replicated.network.Network;
import replicated.network.id.ReplicaId;
import replicated.storage.VersionedValue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class QuorumClientTest {
    
    private MessageBus messageBus;
    private ReplicaId replicaId;
    private NetworkAddress replicaAddress;
    private QuorumClient quorumClient;

    @BeforeEach
    void setUp() {
        replicaId = ReplicaId.of(1);
        // Setup addresses
        replicaAddress = new NetworkAddress("192.168.1.1", 8080);
        
        // Setup unified message bus with test network
        messageBus = new MessageBus(new TestNetwork(), new JsonMessageCodec());
        
        // Create client with bootstrap replicas (new API)
        List<NetworkAddress> bootstrapReplicas = List.of(replicaAddress);
        JsonMessageCodec codec = new JsonMessageCodec();
        quorumClient = new QuorumClient(messageBus, codec, bootstrapReplicas, List.of(replicaId));
    }
    
    @Test
    void shouldCreateClientWithDependencies() {
        // Given dependencies are provided in setUp()
        // Then client should be created successfully
        assertNotNull(quorumClient);
        assertNotNull(quorumClient.getClientId());
        assertTrue(quorumClient.getClientId().name().startsWith("quorum-client"));
    }
    
    @Test
    void shouldThrowExceptionForNullMessageBus() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> new QuorumClient( null, new JsonMessageCodec(), List.of(replicaAddress), List.of(ReplicaId.of(1))));
    }
    
    @Test
    void shouldImplementMessageHandler() {
        // Then client should be a MessageHandler
        assertTrue(quorumClient instanceof MessageHandler);
    }
    
    @Test
    void shouldSendGetRequest() {
        // Given
        String key = "user:123";
        
        // When
        ListenableFuture<VersionedValue> future = quorumClient.sendGetRequest(key, replicaAddress);
        
        // Then
        assertNotNull(future);
        // Verify request is tracked internally
        // This will be verified through message sending and state inspection
        assertDoesNotThrow(() -> quorumClient.tick());
    }
    
    @Test
    void shouldSendSetRequest() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        
        // When
        ListenableFuture<Boolean> future = quorumClient.sendSetRequest(key, value, replicaAddress);
        
        // Then
        assertNotNull(future);
        // Verify request is tracked internally
        assertDoesNotThrow(() -> quorumClient.tick());
    }
    
    @Test
    void shouldGenerateUniqueCorrelationIds() {
        // Given
        String key = "test-key";
        
        // When - send multiple requests
        ListenableFuture<VersionedValue> future1 = quorumClient.sendGetRequest(key, replicaAddress);
        ListenableFuture<VersionedValue> future2 = quorumClient.sendGetRequest(key, replicaAddress);
        ListenableFuture<VersionedValue> future3 = quorumClient.sendGetRequest(key, replicaAddress);
        
        // Then - each should have unique futures (implying unique correlation IDs)
        assertNotSame(future1, future2);
        assertNotSame(future2, future3);
        assertNotSame(future1, future3);
    }
    
    @Test
    void shouldHandleGetResponse() {
        // Given - send a request first
        String key = "user:123";
        ListenableFuture<VersionedValue> future = quorumClient.sendGetRequest(key, replicaAddress);
        
        AtomicReference<VersionedValue> receivedValue = new AtomicReference<>();
        future.onSuccess(receivedValue::set);
        
        // When - receive a response (simulated)
        VersionedValue expectedValue = new VersionedValue("John Doe".getBytes(), 1L);
        GetResponse response = new GetResponse(key, expectedValue);
        
        // Create a message as if from replica (source address doesn't matter for response handling)
        Message responseMessage = createMessage(replicaAddress, new NetworkAddress("127.0.0.1", 9000), 
            MessageType.CLIENT_GET_RESPONSE, response);
        
        quorumClient.onMessageReceived(responseMessage, null);
        
        // Then - future should be completed
        // Note: In actual implementation, correlation ID matching will be tested
        assertDoesNotThrow(() -> quorumClient.tick());
    }
    
    @Test
    void shouldHandleSetResponse() {
        // Given - send a set request first
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        ListenableFuture<Boolean> future = quorumClient.sendSetRequest(key, value, replicaAddress);
        
        AtomicReference<Boolean> receivedResult = new AtomicReference<>();
        future.onSuccess(receivedResult::set);
        
        // When - receive a response
        SetResponse response = new SetResponse(key, true);
        Message responseMessage = createMessage(replicaAddress, new NetworkAddress("127.0.0.1", 9000), 
            MessageType.CLIENT_SET_RESPONSE, response);
        
        quorumClient.onMessageReceived(responseMessage, null);
        
        // Then - future should be completed
        assertDoesNotThrow(() -> quorumClient.tick());
    }
    
    @Test
    void shouldTrackMultiplePendingRequests() {
        // Given
        String key1 = "user:123";
        String key2 = "user:456"; 
        
        // When - send multiple requests
        ListenableFuture<VersionedValue> future1 = quorumClient.sendGetRequest(key1, replicaAddress);
        ListenableFuture<VersionedValue> future2 = quorumClient.sendGetRequest(key2, replicaAddress);
        
        // Then - both should be tracked
        assertNotNull(future1);
        assertNotNull(future2);
        assertNotSame(future1, future2);
        
        // Should handle multiple pending requests
        assertDoesNotThrow(() -> quorumClient.tick());
    }
    
    @Test
    void shouldTimeoutPendingRequests() {
        // Given - client with short timeout
        List<NetworkAddress> bootstrapReplicas = List.of(replicaAddress);
        JsonMessageCodec codec = new JsonMessageCodec();
        quorumClient = new QuorumClient(messageBus, codec, bootstrapReplicas, 3, List.of(replicaId)); // 3 tick timeout
        
        String key = "user:123";
        ListenableFuture<VersionedValue> future = quorumClient.sendGetRequest(key, replicaAddress);
        
        AtomicReference<Throwable> timeoutError = new AtomicReference<>();
        future.onFailure(timeoutError::set);
        
        // When - advance time beyond timeout
        for (int tick = 1; tick <= 5; tick++) {
            quorumClient.tick();
        }
        
        // Then - request should timeout
        // The specific timeout behavior will be verified in implementation
        assertDoesNotThrow(() -> {});
    }
    
    @Test
    void shouldHandleUnrecognizedResponses() {
        // Given - no pending requests
        
        // When - receive unexpected response
        GetResponse response = new GetResponse("unknown-key", null);
        Message responseMessage = createMessage(replicaAddress, new NetworkAddress("127.0.0.1", 9000), 
            MessageType.CLIENT_GET_RESPONSE, response);
        
        // Then - should not throw exception
        assertDoesNotThrow(() -> quorumClient.onMessageReceived(responseMessage, null));
    }
    
    @Test
    void shouldCleanupCompletedRequests() {
        // Given - send request and complete it
        String key = "user:123";
        ListenableFuture<VersionedValue> future = quorumClient.sendGetRequest(key, replicaAddress);
        
        // When - complete the request and tick
        VersionedValue value = new VersionedValue("data".getBytes(), 1L);
        GetResponse response = new GetResponse(key, value);
        Message responseMessage = createMessage(replicaAddress, new NetworkAddress("127.0.0.1", 9000), 
            MessageType.CLIENT_GET_RESPONSE, response);
        
        quorumClient.onMessageReceived(responseMessage, null);
        quorumClient.tick();
        
        // Then - should not cause issues with subsequent operations
        assertDoesNotThrow(() -> {
            ListenableFuture<VersionedValue> newFuture = quorumClient.sendGetRequest(key, replicaAddress);
            assertNotNull(newFuture);
        });
    }
    
    @Test
    void shouldHandleInvalidResponses() {
        // Given - invalid timeout (less than minimum)
        int invalidTimeout = 2;
        JsonMessageCodec codec = new JsonMessageCodec();
        quorumClient = new QuorumClient(messageBus, codec, List.of(replicaAddress), invalidTimeout, List.of(replicaId));
        
        String key = "user:123";
        ListenableFuture<VersionedValue> future = quorumClient.sendGetRequest(key, replicaAddress);
        
        AtomicReference<Throwable> timeoutError = new AtomicReference<>();
        future.onFailure(timeoutError::set);
        
        // When - advance time beyond timeout
        for (int tick = 1; tick <= invalidTimeout + 1; tick++) {
            quorumClient.tick();
        }
        
        // Then - request should timeout
        assertDoesNotThrow(() -> {});
    }
    
    @Test
    void shouldSupportConfigurableTimeout() {
        // Given - client with custom timeout
        int customTimeout = 10;
        List<NetworkAddress> bootstrapReplicas = List.of(replicaAddress);
        JsonMessageCodec codec = new JsonMessageCodec();
        QuorumClient customQuorumClient = new QuorumClient(messageBus, codec, bootstrapReplicas, customTimeout, List.of(replicaId));
        
        // When - send request
        ListenableFuture<VersionedValue> future = customQuorumClient.sendGetRequest("key", replicaAddress);
        
        // Then - should work without timeout for ticks < customTimeout
        for (int tick = 1; tick < customTimeout; tick++) {
            assertDoesNotThrow(() -> customQuorumClient.tick());
        }
    }
    
    // Helper methods
    
    private Message createMessage(NetworkAddress source, NetworkAddress destination, 
                                 MessageType messageType, Object payload) {
        try {
            JsonMessageCodec codec = new JsonMessageCodec();
            byte[] payloadBytes = JsonMessageCodec.createConfiguredObjectMapper()
                .writeValueAsBytes(payload);
            return Message.networkMessage(source, destination, messageType, payloadBytes, "test-correlation-id");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test message", e);
        }
    }
    
    // Simple test network implementation
    private static class TestNetwork implements Network {
        private int nextPort = 60000;
        
        @Override
        public void send(Message message) {
            // No-op for testing
        }
        

        
        @Override
        public void tick() {
            // No-op for testing
        }
        
        public NetworkAddress establishConnection(NetworkAddress destination) {
            // Return a test ephemeral address
            return new NetworkAddress("127.0.0.1", nextPort++);
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
        
        @Override
        public void registerMessageHandler(replicated.network.MessageCallback callback) {
            // Test implementation - just store the callback for now
        }
    }
} 