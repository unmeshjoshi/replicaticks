package replicated.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.JsonMessageCodec;
import replicated.messaging.Message;
import replicated.messaging.MessageType;
import replicated.messaging.NetworkAddress;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

class NioNetworkTest {
    
    private NioNetwork network;
    private NetworkAddress address1;
    private NetworkAddress address2;
    
    @BeforeEach
    void setUp() {
        network = new NioNetwork();
        address1 = new NetworkAddress("127.0.0.1", 9001);
        address2 = new NetworkAddress("127.0.0.1", 9002);
    }
    
    @AfterEach
    void tearDown() {
        if (network != null) {
            network.close();
        }
    }
    
    /**
     * Utility method to run ticks until a condition is met or timeout occurs.
     * This ensures deterministic testing without Thread.sleep().
     */
    private void runUntil(Supplier<Boolean> condition, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (!condition.get()) {
            network.tick();
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                fail("Timeout waiting for condition to be met");
            }
            // Small yield to prevent busy waiting
            Thread.yield();
        }
    }
    
    /**
     * Convenience method with default timeout of 5 seconds.
     */
    private void runUntil(Supplier<Boolean> condition) {
        runUntil(condition, 5000);
    }
    
    @Test
    void shouldCreateNioNetworkSuccessfully() {
        // Given/When
        NioNetwork network = new NioNetwork();
        
        // Then
        assertNotNull(network);
        
        // Cleanup
        network.close();
    }
    
    @Test
    void shouldBindToNetworkAddress() {
        // Given/When/Then - should not throw
        assertDoesNotThrow(() -> network.bind(address1));
    }
    
    @Test
    void shouldThrowExceptionForNullAddressInBind() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> network.bind(null));
    }
    
    @Test
    void shouldUnbindNetworkAddress() {
        // Given
        network.bind(address1);
        
        // When/Then - should not throw
        assertDoesNotThrow(() -> network.unbind(address1));
    }
    
    @Test
    void shouldSupportMessageCallbackRegistration() {
        // Given
        TestMessageHandler handler = new TestMessageHandler();
        
        // When/Then - should not throw
        assertDoesNotThrow(() -> network.registerMessageHandler(handler));
    }
    
    /**
     * Test message handler for callback testing.
     */
    private static class TestMessageHandler implements MessageCallback {
        @Override
        public void onMessage(Message message, MessageContext context) {
            // Test implementation - no-op
        }
    }
    
    @Test
    void shouldAcceptMessagesForSending() {
        // Given
        Message message = new Message(address1, address2, MessageType.CLIENT_GET_REQUEST, "test".getBytes(), "test-correlation-id");
        
        // When/Then - should not throw
        assertDoesNotThrow(() -> network.send(message));
    }
    
    @Test
    void shouldThrowExceptionForNullMessageInSend() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> network.send(null));
    }
    
    @Test
    void shouldProcessTickWithoutError() {
        // Given
        network.bind(address1);
        
        // When/Then - should not throw
        assertDoesNotThrow(() -> network.tick());
    }
    
    @Test
    void shouldSupportNetworkPartitioning() {
        // Given/When/Then - should not throw
        assertDoesNotThrow(() -> network.partition(address1, address2));
        assertDoesNotThrow(() -> network.partitionOneWay(address1, address2));
        assertDoesNotThrow(() -> network.healPartition(address1, address2));
    }
    
    @Test
    void shouldSupportPerLinkConfiguration() {
        // Given/When/Then - should not throw
        assertDoesNotThrow(() -> network.setDelay(address1, address2, 5));
        assertDoesNotThrow(() -> network.setPacketLoss(address1, address2, 0.1));
    }
    
    @Test
    void shouldThrowExceptionForInvalidDelayTicks() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> 
            network.setDelay(address1, address2, -1));
    }
    
    @Test
    void shouldThrowExceptionForInvalidPacketLossRate() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> 
            network.setPacketLoss(address1, address2, -0.1));
        assertThrows(IllegalArgumentException.class, () -> 
            network.setPacketLoss(address1, address2, 1.1));
    }
    
    @Test
    void shouldCloseResourcesGracefully() {
        // Given
        // Use a different network because the teardown closes the network created in the setup.

        NioNetwork testNetwork = new NioNetwork();
        testNetwork.bind(address1);
        
        // When/Then - should not throw
        assertDoesNotThrow(() -> testNetwork.close());
    }
    
    @Test
    void shouldRespectMaxOutboundPerTickLimit() {
        // Given
        network.bind(address1);

        int noOfPerTickMessages = 3;
        NetworkConfig config = NetworkConfig.builder()
            .maxOutboundPerTick(noOfPerTickMessages)  // Limit to 3 messages per tick
            .build();
        NioNetwork limitedNetwork = new NioNetwork(new JsonMessageCodec(), config);
        
        // Send 10 messages (more than the limit)
        int totalNoOfMessages = 10;
        for (int i = 0; i < totalNoOfMessages; i++) {
            Message message = new Message(address2, address1, MessageType.CLIENT_GET_REQUEST,
                ("test-" + i).getBytes(), "correlation-" + i);
            limitedNetwork.send(message);
        }
        
        // When - process one tick
        limitedNetwork.tick();

        assertEquals(limitedNetwork.getOutboundQueueSize(), totalNoOfMessages - noOfPerTickMessages ,
            "Messages remaining, indicating limit was applied. Found: " + limitedNetwork.getOutboundQueueSize());

        // Cleanup
        limitedNetwork.close();
    }
    
    @Test
    void shouldProcessAllMessagesWhenUnderLimit() {
        network.bind(address2);
        // Given
        NetworkConfig config = NetworkConfig.builder()
            .maxOutboundPerTick(10)  // Limit higher than message count
            .build();
        NioNetwork limitedNetwork = new NioNetwork(new JsonMessageCodec(), config);

        // Send 5 messages (under the limit)
        for (int i = 0; i < 5; i++) {
            Message message = new Message(address1, address2, MessageType.CLIENT_GET_REQUEST,
                ("test-" + i).getBytes(), "correlation-" + i);
            limitedNetwork.send(message);
        }
        
        // When - process one tick
        limitedNetwork.tick();

        // Then - all messages should have been processed
        assertEquals(0, limitedNetwork.getOutboundQueueSize(), 
            "Should have 0 messages remaining when under the limit");
        
        // Cleanup
        limitedNetwork.close();
    }

    @Test
    void shouldMaintainConnectionMapping() throws IOException {
        // Create network with two addresses
        NetworkAddress serverAddress1 = new NetworkAddress("127.0.0.1", 9001);
        NetworkAddress serverAddress2 = new NetworkAddress("127.0.0.1", 9002);
        
        NioNetwork network1 = new NioNetwork();
        NioNetwork network2 = new NioNetwork();
        
        try {
            // Bind both networks
            network1.bind(serverAddress1);
            network2.bind(serverAddress2);
            
            // Establish outbound connection from network1 to network2
            NetworkAddress clientAddress = network1.establishConnection(serverAddress2);
            
            // Run ticks until connections are established
            runUntil(() -> {
                network1.tick();
                network2.tick();
                return (network1.getOutboundConnections().size() == 1 &&
                network2.getInboundConnections().size() == 1);},
                5000);


            assertTrue(network1.getInboundConnections().isEmpty());

            // Verify inbound connection is stored in inbound map on network2

            assertTrue(network2.getOutboundConnections().isEmpty());
            
            // Verify connection direction logic - both maps should have exactly one connection
            assertEquals(1, network1.getOutboundConnections().size());
            assertEquals(1, network2.getInboundConnections().size());
            
        } finally {
            network1.close();
            network2.close();
        }
    }

    @Test
    void shouldProcessMessagesWithSeparateQueues() throws IOException {
        // Create network with three addresses
        NetworkAddress serverAddress1 = new NetworkAddress("127.0.0.1", 9001);
        NetworkAddress serverAddress2 = new NetworkAddress("127.0.0.1", 9002);
        NetworkAddress serverAddress3 = new NetworkAddress("127.0.0.1", 9003);
        
        NioNetwork network1 = new NioNetwork();
        NioNetwork network2 = new NioNetwork();
        NioNetwork network3 = new NioNetwork();
        
        try {
            // Bind all networks
            network1.bind(serverAddress1);
            network2.bind(serverAddress2);
            network3.bind(serverAddress3);
            
            // Establish connections
            NetworkAddress clientAddress1 = network1.establishConnection(serverAddress2);
            NetworkAddress clientAddress2 = network1.establishConnection(serverAddress3);
            
            // Run ticks until connections are established
            runUntil(() -> {
                network1.tick();
                network2.tick();
                network3.tick();
                System.out.println("network1.getOutboundConnections().size() = " + network1.getOutboundConnections().size());
                System.out.println("network2.getInboundConnections().size() = " + network2.getInboundConnections().size());
                System.out.println("network3.getInboundConnections().size() = " + network3.getInboundConnections().size());

                return (network1.getOutboundConnections().size() == 2 &&
                        network2.getInboundConnections().size() == 1 && network3.getInboundConnections().size() == 1);
            }, 5000);
            
            // Send messages to different destinations
            Message msg1 = new Message(serverAddress1, serverAddress2, MessageType.CLIENT_GET_REQUEST, "test1".getBytes(), "corr1");
            Message msg2 = new Message(serverAddress1, serverAddress3, MessageType.CLIENT_GET_REQUEST, "test2".getBytes(), "corr2");
            Message msg3 = new Message(serverAddress1, serverAddress2, MessageType.CLIENT_GET_REQUEST, "test3".getBytes(), "corr3");
            Message msg4 = new Message(serverAddress1, serverAddress3, MessageType.CLIENT_GET_REQUEST, "test4".getBytes(), "corr4");
            
            network1.send(msg1);
            network1.send(msg2);
            network1.send(msg3);
            network1.send(msg4);
            
            // Verify messages are queued in separate destination queues
            assertEquals(2, network1.getOutboundQueueSizeForDestination(serverAddress2));
            assertEquals(2, network1.getOutboundQueueSizeForDestination(serverAddress3));
            
            // Process outbound messages
            network1.tick();
            
            // Verify round-robin processing (should process from both destinations)
            assertTrue(network1.getOutboundQueueSizeForDestination(serverAddress2) < 2 || 
                      network1.getOutboundQueueSizeForDestination(serverAddress3) < 2);
            
        } finally {
            network1.close();
            network2.close();
            network3.close();
        }
    }
} 