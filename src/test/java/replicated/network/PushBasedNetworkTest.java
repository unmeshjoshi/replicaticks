package replicated.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import replicated.messaging.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for push-based network delivery where Network.tick() calls registered callbacks
 * instead of requiring components to poll via receive() method.
 */
public class PushBasedNetworkTest {
    
    private SimulatedNetwork network;
    private TestMessageHandler messageHandler;
    
    @BeforeEach
    void setUp() {
        network = new SimulatedNetwork(new Random(42), 1, 0.0); // 1 tick delay
        messageHandler = new TestMessageHandler();
    }
    
    @Test
    void shouldDeliverMessageViaPushBasedCallback() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                    "test".getBytes(), "test-correlation-id");
        
        // When - message is sent
        network.send(message);
        
        // Then - message should not be delivered yet (1 tick delay)
        assertEquals(0, messageHandler.receivedMessages.size());
        
        // When - network ticks (processes pending messages)
        network.tick();
        
        // Then - callback should be invoked with the message
        assertEquals(1, messageHandler.receivedMessages.size());
        Message receivedMessage = messageHandler.receivedMessages.get(0);
        assertEquals(message.source(), receivedMessage.source());
        assertEquals(message.destination(), receivedMessage.destination());
        assertEquals(message.messageType(), receivedMessage.messageType());
        assertEquals(message.correlationId(), receivedMessage.correlationId());
    }
    
    @Test
    void shouldDeliverMultipleMessagesInOrder() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        
        Message message1 = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                     "test1".getBytes(), "correlation-1");
        Message message2 = new Message(source, destination, MessageType.CLIENT_SET_REQUEST, 
                                     "test2".getBytes(), "correlation-2");
        
        // When - multiple messages are sent
        network.send(message1);
        network.send(message2);
        
        // Then - no messages delivered yet
        assertEquals(0, messageHandler.receivedMessages.size());
        
        // When - network ticks
        network.tick();
        
        // Then - both messages should be delivered in order
        assertEquals(2, messageHandler.receivedMessages.size());
        assertEquals("correlation-1", messageHandler.receivedMessages.get(0).correlationId());
        assertEquals("correlation-2", messageHandler.receivedMessages.get(1).correlationId());
    }
    
    @Test
    void shouldNotDeliverMessagesWithoutRegisteredCallback() {
        // Given - network with no registered callback
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                    "test".getBytes(), "test-correlation-id");
        
        // When - message is sent and network ticks
        network.send(message);
        network.tick();
        
        // Then - no callback should be invoked (no registered handler)
        assertEquals(0, messageHandler.receivedMessages.size());
    }
    
    @Test
    void shouldHandlePacketLossInPushBasedDelivery() {
        // Given - network with 100% packet loss
        network = new SimulatedNetwork(new Random(42), 1, 1.0); // 100% packet loss
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                    "test".getBytes(), "test-correlation-id");
        
        // When - message is sent and network ticks
        network.send(message);
        network.tick();
        
        // Then - callback should not be invoked (message lost)
        assertEquals(0, messageHandler.receivedMessages.size());
    }
    
    @Test
    void shouldRespectNetworkDelaysInPushBasedDelivery() {
        // Given - network with 3 tick delay
        network = new SimulatedNetwork(new Random(42), 3, 0.0); // 3 tick delay
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, 
                                    "test".getBytes(), "test-correlation-id");
        
        // When - message is sent
        network.send(message);
        
        // Then - message should not be delivered for first 2 ticks
        network.tick(); // tick 1
        assertEquals(0, messageHandler.receivedMessages.size());
        
        network.tick(); // tick 2
        assertEquals(0, messageHandler.receivedMessages.size());
        
        // When - network ticks for the 3rd time (3-tick delay means delivery at tick 3)
        network.tick(); // tick 3
        
        // Then - message should be delivered
        assertEquals(1, messageHandler.receivedMessages.size());
    }
    
    /**
     * Test message handler that collects received messages for verification.
     */
    private static class TestMessageHandler implements MessageCallback {
        public final List<Message> receivedMessages = new ArrayList<>();
        public final List<MessageContext> receivedContexts = new ArrayList<>();
        
        @Override
        public void onMessage(Message message, MessageContext context) {
            receivedMessages.add(message);
            receivedContexts.add(context);
        }
    }
} 