package replicated.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import replicated.messaging.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for MessageCallback interface and callback-based message delivery.
 * This demonstrates the push-based architecture where Network.tick() calls
 * registered callbacks instead of components polling via receive().
 */
public class MessageCallbackTest {
    
    private SimulatedNetwork network;
    private TestMessageCallback callback;
    
    @BeforeEach
    void setUp() {
        network = new SimulatedNetwork(new Random(42), 1, 0.0);
        callback = new TestMessageCallback();
    }
    
    @Test
    void shouldCallRegisteredCallbackWhenMessageIsReady() {
        // Given - network with registered callback
        network.registerMessageHandler(callback);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                    "test".getBytes(), "test-correlation-id");
        
        // When - message is sent and network ticks
        network.send(message);
        network.tick(); // Should process queues and call callback
        
        // Then - callback should be invoked with the message
        assertEquals(1, callback.receivedMessages.size());
        Message receivedMessage = callback.receivedMessages.get(0);
        assertEquals(message.source(), receivedMessage.source());
        assertEquals(message.destination(), receivedMessage.destination());
        assertEquals(message.messageType(), receivedMessage.messageType());
        assertEquals(message.correlationId(), receivedMessage.correlationId());
    }
    
    @Test
    void shouldCallCallbackWithMessageContext() {
        // Given - network with registered callback
        network.registerMessageHandler(callback);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                    "test".getBytes(), "test-correlation-id");
        
        // When - message is sent and network ticks
        network.send(message);
        network.tick();
        
        // Then - callback should be invoked with MessageContext
        assertEquals(1, callback.receivedContexts.size());
        MessageContext context = callback.receivedContexts.get(0);
        assertNotNull(context);
        // Context should contain the message information
        // (specific context validation depends on MessageContext implementation)
    }
    
    @Test
    void shouldReplaceCallbackWhenRegisteredMultipleTimes() {
        // Given - network with first callback registered
        TestMessageCallback callback2 = new TestMessageCallback();
        network.registerMessageHandler(callback);
        
        // When - register a second callback (should replace the first)
        network.registerMessageHandler(callback2);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                    "test".getBytes(), "test-correlation-id");
        
        // When - message is sent and network ticks
        network.send(message);
        network.tick();
        
        // Then - only the second callback should be invoked
        assertEquals(0, callback.receivedMessages.size());
        assertEquals(1, callback2.receivedMessages.size());
    }
    
    /**
     * Test implementation of MessageCallback for testing purposes.
     */
    private static class TestMessageCallback implements MessageCallback {
        public final List<Message> receivedMessages = new ArrayList<>();
        public final List<MessageContext> receivedContexts = new ArrayList<>();
        
        @Override
        public void onMessage(Message message, MessageContext context) {
            receivedMessages.add(message);
            receivedContexts.add(context);
        }
    }
} 