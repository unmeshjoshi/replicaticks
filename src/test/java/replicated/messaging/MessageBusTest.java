package replicated.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.network.MessageContext;
import replicated.network.Network;
import replicated.network.SimulatedNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MessageBusTest {
    
    private Network network;
    private MessageCodec codec;
    private ServerMessageBus messageBus;
    private NetworkAddress nodeA;
    private NetworkAddress nodeB;
    
    @BeforeEach
    void setUp() {
        network = new SimulatedNetwork(new Random(42L));
        codec = new JsonMessageCodec();
        messageBus = new ServerMessageBus(network, codec);
        
        // Setup message bus multiplexer to handle message delivery
        MessageBusMultiplexer multiplexer = new MessageBusMultiplexer(network);
        multiplexer.registerMessageBus(messageBus);
        
        nodeA = new NetworkAddress("192.168.1.1", 8080);
        nodeB = new NetworkAddress("192.168.1.2", 8080);
    }
    
    @Test
    void shouldCreateMessageBusWithDependencies() {
        // Given dependencies are provided in setUp()
        
        // Then - MessageBus should be created successfully
        assertNotNull(messageBus);
    }
    
    @Test
    void shouldThrowExceptionForNullNetwork() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> new ServerMessageBus(null, codec));
    }
    
    @Test
    void shouldThrowExceptionForNullCodec() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> new ServerMessageBus(network, null));
    }
    
    @Test
    void shouldSendMessageThroughNetwork() {
        // Given
        Message message = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "test".getBytes(), "test-correlation-id");
        
        // When
        messageBus.sendMessage(message);
        tick();


        // Then - message should be sent through network (callback-based approach handles delivery)
        // This test verifies the basic send functionality works without exceptions
        assertDoesNotThrow(() -> messageBus.sendMessage(message));
    }
    
    @Test
    void shouldThrowExceptionForNullMessage() {
        // When & Then
        assertThrows(IllegalArgumentException.class, 
            () -> messageBus.sendMessage(null));
    }
    
    @Test
    void shouldAllowComponentRegistration() {
        // Given
        TestMessageHandler handler = new TestMessageHandler();
        
        // When
        messageBus.registerHandler(nodeA, handler);
        
        // Then - registration should succeed without exception
        // Actual message routing will be tested separately
    }
    
    @Test
    void shouldRouteMessagesToRegisteredHandlers() {
        // Given
        TestMessageHandler handlerA = new TestMessageHandler();
        TestMessageHandler handlerB = new TestMessageHandler();
        
        messageBus.registerHandler(nodeA, handlerA);
        messageBus.registerHandler(nodeB, handlerB);
        
        Message messageToA = new Message(nodeB, nodeA, MessageType.CLIENT_GET_REQUEST, "to-A".getBytes(), "test-correlation-id-1");
        Message messageToB = new Message(nodeA, nodeB, MessageType.CLIENT_SET_REQUEST, "to-B".getBytes(), "test-correlation-id-2");
        
        // When
        messageBus.sendMessage(messageToA);
        messageBus.sendMessage(messageToB);

        tick();

        // Then
        assertEquals(1, handlerA.getReceivedMessages().size());
        assertEquals(messageToA, handlerA.getReceivedMessages().get(0));
        
        assertEquals(1, handlerB.getReceivedMessages().size());
        assertEquals(messageToB, handlerB.getReceivedMessages().get(0));
    }

    private void tick() {
        network.tick();
        messageBus.tick();
    }

    @Test
    void shouldHandleUnregisteredAddresses() {
        // Given
        Message message = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "unregistered".getBytes(), "test-correlation-id-3");
        
        // When
        messageBus.sendMessage(message);
        tick();

        // Then - should not throw exception, message just won't be routed
        // With callback-based approach, message is sent but not delivered to any handler
        assertDoesNotThrow(() -> messageBus.sendMessage(message));
    }
    
    @Test
    void shouldDelegateNetworkTick() {
        // Given
        Message message = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "test".getBytes(), "test-correlation-id-4");
        messageBus.sendMessage(message);
        
        // When
        tick();

        // Then - message should be processed by underlying network (callback-based approach)
        assertDoesNotThrow(() -> messageBus.tick());
    }
    
    @Test
    void shouldSupportBroadcastMessaging() {
        // Given
        NetworkAddress nodeC = new NetworkAddress("192.168.1.3", 8080);
        TestMessageHandler handlerA = new TestMessageHandler();
        TestMessageHandler handlerB = new TestMessageHandler();
        TestMessageHandler handlerC = new TestMessageHandler();
        
        messageBus.registerHandler(nodeA, handlerA);
        messageBus.registerHandler(nodeB, handlerB);
        messageBus.registerHandler(nodeC, handlerC);
        
        List<NetworkAddress> recipients = List.of(nodeA, nodeB, nodeC);
        
        // When
        messageBus.broadcast(nodeA, recipients, MessageType.INTERNAL_GET_REQUEST, "broadcast".getBytes());
        tick();

        // Then - all recipients except sender should receive the message
        assertEquals(0, handlerA.getReceivedMessages().size()); // sender doesn't receive
        assertEquals(1, handlerB.getReceivedMessages().size());
        assertEquals(1, handlerC.getReceivedMessages().size());
        
        Message receivedB = handlerB.getReceivedMessages().get(0);
        assertEquals(nodeA, receivedB.source());
        assertEquals(nodeB, receivedB.destination());
        assertEquals(MessageType.INTERNAL_GET_REQUEST, receivedB.messageType());
    }
    
    @Test
    void shouldSupportUnregisteringHandlers() {
        // Given
        TestMessageHandler handler = new TestMessageHandler();
        messageBus.registerHandler(nodeA, handler);
        
        // When
        messageBus.unregisterHandler(nodeA);
        
        Message message = new Message(nodeB, nodeA, MessageType.CLIENT_GET_REQUEST, "test".getBytes(), "test-correlation-id-6");
        messageBus.sendMessage(message);

        tick();

        // Then - handler should not receive message
        assertTrue(handler.getReceivedMessages().isEmpty());
        
        // Message is sent but not delivered to any handler (callback-based approach)
        assertDoesNotThrow(() -> messageBus.sendMessage(message));
    }
    
    @Test
    void shouldHandleMultipleMessagesPerTick() {
        // Given
        TestMessageHandler handler = new TestMessageHandler();
        messageBus.registerHandler(nodeB, handler);
        
        Message message1 = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "msg1".getBytes(), "test-correlation-id-7");
        Message message2 = new Message(nodeA, nodeB, MessageType.CLIENT_SET_REQUEST, "msg2".getBytes(), "test-correlation-id-8");
        Message message3 = new Message(nodeA, nodeB, MessageType.INTERNAL_GET_REQUEST, "msg3".getBytes(), "test-correlation-id-9");
        
        // When
        messageBus.sendMessage(message1);
        messageBus.sendMessage(message2);
        messageBus.sendMessage(message3);

        tick();

        // Then - all messages should be delivered to handler
        List<Message> received = handler.getReceivedMessages();
        assertEquals(3, received.size());
        assertTrue(received.contains(message1));
        assertTrue(received.contains(message2));
        assertTrue(received.contains(message3));
    }
    
    @Test
    void shouldMaintainMessageOrderWithinTick() {
        // Given
        TestMessageHandler handler = new TestMessageHandler();
        messageBus.registerHandler(nodeB, handler);
        
        Message message1 = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "first".getBytes(), "test-correlation-id-10");
        Message message2 = new Message(nodeA, nodeB, MessageType.CLIENT_SET_REQUEST, "second".getBytes(), "test-correlation-id-11");
        
        // When - send in specific order
        messageBus.sendMessage(message1);
        messageBus.sendMessage(message2);

        tick();

        // Then - should receive in same order
        List<Message> received = handler.getReceivedMessages();
        assertEquals(2, received.size());
        assertEquals(message1, received.get(0));
        assertEquals(message2, received.get(1));
    }
    
    // Test helper class
    static class TestMessageHandler implements MessageHandler {
        private final List<Message> receivedMessages = new ArrayList<>();
        
        @Override
        public void onMessageReceived(Message message, MessageContext ctx) {
            receivedMessages.add(message);
        }
        
        public List<Message> getReceivedMessages() {
            return new ArrayList<>(receivedMessages);
        }
    }
} 