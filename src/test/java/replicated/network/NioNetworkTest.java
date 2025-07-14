package replicated.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.Message;
import replicated.messaging.MessageType;
import replicated.messaging.NetworkAddress;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

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
} 