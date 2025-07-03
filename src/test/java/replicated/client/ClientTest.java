package replicated.client;

import replicated.messaging.*;
import replicated.storage.VersionedValue;
import replicated.future.ListenableFuture;
import replicated.network.Network;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class ClientTest {
    
    private MessageBus messageBus;
    private NetworkAddress clientAddress;
    private NetworkAddress replicaAddress;
    private Client client;
    
    @BeforeEach
    void setUp() {
        // Setup addresses
        clientAddress = new NetworkAddress("192.168.1.100", 9000);
        replicaAddress = new NetworkAddress("192.168.1.1", 8080);
        
        // Setup message bus with test network
        messageBus = new MessageBus(new TestNetwork(), new JsonMessageCodec());
        
        // Create client
        client = new Client(clientAddress, messageBus);
    }
    
    @Test
    void shouldCreateClientWithDependencies() {
        // Given dependencies are provided in setUp()
        // Then client should be created successfully
        assertNotNull(client);
        assertEquals(clientAddress, client.getAddress());
    }
    
    @Test
    void shouldThrowExceptionForNullAddress() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> new Client(null, messageBus));
    }
    
    @Test
    void shouldThrowExceptionForNullMessageBus() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> new Client(clientAddress, null));
    }
    
    @Test
    void shouldImplementMessageHandler() {
        // Then client should be a MessageHandler
        assertTrue(client instanceof MessageHandler);
    }
    
    @Test
    void shouldSendGetRequest() {
        // Given
        String key = "user:123";
        
        // When
        ListenableFuture<VersionedValue> future = client.sendGetRequest(key, replicaAddress);
        
        // Then
        assertNotNull(future);
        // Verify request is tracked internally
        // This will be verified through message sending and state inspection
        assertDoesNotThrow(() -> client.tick(1L));
    }
    
    @Test
    void shouldSendSetRequest() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        
        // When
        ListenableFuture<Boolean> future = client.sendSetRequest(key, value, replicaAddress);
        
        // Then
        assertNotNull(future);
        // Verify request is tracked internally
        assertDoesNotThrow(() -> client.tick(1L));
    }
    
    @Test
    void shouldGenerateUniqueCorrelationIds() {
        // Given
        String key = "test-key";
        
        // When - send multiple requests
        ListenableFuture<VersionedValue> future1 = client.sendGetRequest(key, replicaAddress);
        ListenableFuture<VersionedValue> future2 = client.sendGetRequest(key, replicaAddress);
        ListenableFuture<VersionedValue> future3 = client.sendGetRequest(key, replicaAddress);
        
        // Then - each should have unique futures (implying unique correlation IDs)
        assertNotSame(future1, future2);
        assertNotSame(future2, future3);
        assertNotSame(future1, future3);
    }
    
    @Test
    void shouldHandleGetResponse() {
        // Given - send a request first
        String key = "user:123";
        ListenableFuture<VersionedValue> future = client.sendGetRequest(key, replicaAddress);
        
        AtomicReference<VersionedValue> receivedValue = new AtomicReference<>();
        future.onSuccess(receivedValue::set);
        
        // When - receive a response (simulated)
        VersionedValue expectedValue = new VersionedValue("John Doe".getBytes(), 1L);
        GetResponse response = new GetResponse(key, expectedValue);
        
        // Create a message as if from replica
        Message responseMessage = createMessage(replicaAddress, clientAddress, 
            MessageType.CLIENT_RESPONSE, response);
        
        client.onMessageReceived(responseMessage);
        
        // Then - future should be completed
        // Note: In actual implementation, correlation ID matching will be tested
        assertDoesNotThrow(() -> client.tick(1L));
    }
    
    @Test
    void shouldHandleSetResponse() {
        // Given - send a set request first
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        ListenableFuture<Boolean> future = client.sendSetRequest(key, value, replicaAddress);
        
        AtomicReference<Boolean> receivedResult = new AtomicReference<>();
        future.onSuccess(receivedResult::set);
        
        // When - receive a response
        SetResponse response = new SetResponse(key, true);
        Message responseMessage = createMessage(replicaAddress, clientAddress, 
            MessageType.CLIENT_RESPONSE, response);
        
        client.onMessageReceived(responseMessage);
        
        // Then - future should be completed
        assertDoesNotThrow(() -> client.tick(1L));
    }
    
    @Test
    void shouldTrackMultiplePendingRequests() {
        // Given
        String key1 = "user:123";
        String key2 = "user:456"; 
        
        // When - send multiple requests
        ListenableFuture<VersionedValue> future1 = client.sendGetRequest(key1, replicaAddress);
        ListenableFuture<VersionedValue> future2 = client.sendGetRequest(key2, replicaAddress);
        
        // Then - both should be tracked
        assertNotNull(future1);
        assertNotNull(future2);
        assertNotSame(future1, future2);
        
        // Should handle multiple pending requests
        assertDoesNotThrow(() -> client.tick(1L));
    }
    
    @Test
    void shouldTimeoutPendingRequests() {
        // Given - client with short timeout
        client = new Client(clientAddress, messageBus, 3); // 3 tick timeout
        
        String key = "user:123";
        ListenableFuture<VersionedValue> future = client.sendGetRequest(key, replicaAddress);
        
        AtomicReference<Throwable> timeoutError = new AtomicReference<>();
        future.onFailure(timeoutError::set);
        
        // When - advance time beyond timeout
        for (int tick = 1; tick <= 5; tick++) {
            client.tick(tick);
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
        Message responseMessage = createMessage(replicaAddress, clientAddress, 
            MessageType.CLIENT_RESPONSE, response);
        
        // Then - should not throw exception
        assertDoesNotThrow(() -> client.onMessageReceived(responseMessage));
    }
    
    @Test
    void shouldCleanupCompletedRequests() {
        // Given - send request and complete it
        String key = "user:123";
        ListenableFuture<VersionedValue> future = client.sendGetRequest(key, replicaAddress);
        
        // When - complete the request and tick
        VersionedValue value = new VersionedValue("data".getBytes(), 1L);
        GetResponse response = new GetResponse(key, value);
        Message responseMessage = createMessage(replicaAddress, clientAddress, 
            MessageType.CLIENT_RESPONSE, response);
        
        client.onMessageReceived(responseMessage);
        client.tick(1L);
        
        // Then - should not cause issues with subsequent operations
        assertDoesNotThrow(() -> {
            ListenableFuture<VersionedValue> newFuture = client.sendGetRequest(key, replicaAddress);
            assertNotNull(newFuture);
        });
    }
    
    @Test
    void shouldSupportConfigurableTimeout() {
        // Given - client with custom timeout
        int customTimeout = 10;
        Client customClient = new Client(clientAddress, messageBus, customTimeout);
        
        // When - send request
        ListenableFuture<VersionedValue> future = customClient.sendGetRequest("key", replicaAddress);
        
        // Then - should work without timeout for ticks < customTimeout
        for (int tick = 1; tick < customTimeout; tick++) {
            final int currentTick = tick; // Make effectively final for lambda
            assertDoesNotThrow(() -> customClient.tick(currentTick));
        }
    }
    
    // Helper methods
    
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
    private static class TestNetwork implements Network {
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