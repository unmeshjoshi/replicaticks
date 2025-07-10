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

/**
 * Tests for the unified MessageBus class that handles both correlation ID and address-based routing.
 * This replaces the separate ClientMessageBus and ServerMessageBus classes.
 */
class UnifiedMessageBusTest {
    
    private Network network;
    private MessageCodec codec;
    private MessageBus messageBus;
    private NetworkAddress serverAddress;
    private NetworkAddress clientAddress;
    
    @BeforeEach
    void setUp() {
        network = new SimulatedNetwork(new Random(42L));
        codec = new JsonMessageCodec();
        messageBus = new MessageBus(network, codec);
        
        // Register messageBus directly with network (no multiplexer needed)
        network.registerMessageHandler(messageBus);
        
        serverAddress = new NetworkAddress("192.168.1.1", 8080);
        clientAddress = new NetworkAddress("192.168.1.2", 9000);
    }
    
    @Test
    void shouldRouteByCorrelationIdFirst() {
        // Given - a handler registered for correlation ID and address
        TestMessageHandler correlationHandler = new TestMessageHandler();
        TestMessageHandler addressHandler = new TestMessageHandler();
        
        String correlationId = "test-correlation-123";
        messageBus.registerHandler(correlationId, correlationHandler);
        messageBus.registerHandler(serverAddress, addressHandler);
        
        // When - sending a message with both correlation ID and destination address
        Message message = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_RESPONSE, 
                                    "test-payload".getBytes(), correlationId);
        MessageContext context = new MessageContext(message);
        
        messageBus.onMessage(message, context);
        
        // Then - correlation ID handler should receive the message (higher priority)
        assertEquals(1, correlationHandler.receivedMessages.size());
        assertEquals(message, correlationHandler.receivedMessages.get(0));
        
        // And - address handler should NOT receive the message
        assertEquals(0, addressHandler.receivedMessages.size());
    }
    
    @Test
    void shouldFallbackToAddressRouting() {
        // Given - only an address handler registered
        TestMessageHandler addressHandler = new TestMessageHandler();
        messageBus.registerHandler(serverAddress, addressHandler);
        
        // When - sending a message with correlation ID but no correlation handler
        Message message = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_REQUEST, 
                                    "test-payload".getBytes(), "unregistered-correlation-id");
        MessageContext context = new MessageContext(message);
        
        messageBus.onMessage(message, context);
        
        // Then - address handler should receive the message
        assertEquals(1, addressHandler.receivedMessages.size());
        assertEquals(message, addressHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldRemoveCorrelationHandlerAfterUse() {
        // Given - a correlation handler registered
        TestMessageHandler correlationHandler = new TestMessageHandler();
        String correlationId = "test-correlation-456";
        messageBus.registerHandler(correlationId, correlationHandler);
        
        // When - sending a message with the correlation ID
        Message message = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_RESPONSE, 
                                    "test-payload".getBytes(), correlationId);
        MessageContext context = new MessageContext(message);
        
        messageBus.onMessage(message, context);
        
        // Then - handler should receive the message
        assertEquals(1, correlationHandler.receivedMessages.size());
        
        // When - sending another message with the same correlation ID
        TestMessageHandler addressHandler = new TestMessageHandler();
        messageBus.registerHandler(serverAddress, addressHandler);
        
        Message secondMessage = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_RESPONSE, 
                                          "second-payload".getBytes(), correlationId);
        messageBus.onMessage(secondMessage, new MessageContext(secondMessage));
        
        // Then - correlation handler should not receive the second message (removed after first use)
        assertEquals(1, correlationHandler.receivedMessages.size());
        
        // And - address handler should receive the second message (fallback)
        assertEquals(1, addressHandler.receivedMessages.size());
        assertEquals(secondMessage, addressHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldHandleBothRoutingTypesIndependently() {
        // Given - handlers for different correlation IDs and addresses
        TestMessageHandler correlationHandler1 = new TestMessageHandler();
        TestMessageHandler correlationHandler2 = new TestMessageHandler();
        TestMessageHandler addressHandler1 = new TestMessageHandler();
        TestMessageHandler addressHandler2 = new TestMessageHandler();
        
        String correlationId1 = "corr-1";
        String correlationId2 = "corr-2";
        NetworkAddress address1 = new NetworkAddress("10.0.0.1", 8080);
        NetworkAddress address2 = new NetworkAddress("10.0.0.2", 8080);
        
        messageBus.registerHandler(correlationId1, correlationHandler1);
        messageBus.registerHandler(correlationId2, correlationHandler2);
        messageBus.registerHandler(address1, addressHandler1);
        messageBus.registerHandler(address2, addressHandler2);
        
        // When - sending messages with different routing patterns
        Message corrMessage1 = new Message(serverAddress, address1, MessageType.CLIENT_GET_RESPONSE, 
                                         "corr1".getBytes(), correlationId1);
        Message corrMessage2 = new Message(serverAddress, address2, MessageType.CLIENT_GET_RESPONSE, 
                                         "corr2".getBytes(), correlationId2);
        Message addrMessage1 = new Message(clientAddress, address1, MessageType.CLIENT_GET_REQUEST, 
                                         "addr1".getBytes(), "unused-corr-id");
        Message addrMessage2 = new Message(clientAddress, address2, MessageType.CLIENT_SET_REQUEST, 
                                         "addr2".getBytes(), "unused-corr-id");
        
        messageBus.onMessage(corrMessage1, new MessageContext(corrMessage1));
        messageBus.onMessage(corrMessage2, new MessageContext(corrMessage2));
        messageBus.onMessage(addrMessage1, new MessageContext(addrMessage1));
        messageBus.onMessage(addrMessage2, new MessageContext(addrMessage2));
        
        // Then - each handler should receive only its intended message
        assertEquals(1, correlationHandler1.receivedMessages.size());
        assertEquals(corrMessage1, correlationHandler1.receivedMessages.get(0));
        
        assertEquals(1, correlationHandler2.receivedMessages.size());
        assertEquals(corrMessage2, correlationHandler2.receivedMessages.get(0));
        
        assertEquals(1, addressHandler1.receivedMessages.size());
        assertEquals(addrMessage1, addressHandler1.receivedMessages.get(0));
        
        assertEquals(1, addressHandler2.receivedMessages.size());
        assertEquals(addrMessage2, addressHandler2.receivedMessages.get(0));
    }
    
    @Test
    void shouldNotCrashOnUnroutableMessages() {
        // Given - no handlers registered
        
        // When - sending a message with no matching handlers
        Message message = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_REQUEST, 
                                    "unroutable".getBytes(), "unregistered-correlation-id");
        MessageContext context = new MessageContext(message);
        
        // Then - should not throw exception (just log the unroutable message)
        assertDoesNotThrow(() -> messageBus.onMessage(message, context));
    }
    
    @Test
    void shouldSupportHandlerRegistrationAndUnregistration() {
        // Given - handlers
        TestMessageHandler correlationHandler = new TestMessageHandler();
        TestMessageHandler addressHandler = new TestMessageHandler();
        
        String correlationId = "test-corr";
        
        // When - registering handlers
        messageBus.registerHandler(correlationId, correlationHandler);
        messageBus.registerHandler(serverAddress, addressHandler);
        
        // Then - handlers should be registered
        Message corrMessage = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_RESPONSE, 
                                        "test".getBytes(), correlationId);
        messageBus.onMessage(corrMessage, new MessageContext(corrMessage));
        assertEquals(1, correlationHandler.receivedMessages.size());
        
        Message addrMessage = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_REQUEST, 
                                        "test".getBytes(), "other-corr");
        messageBus.onMessage(addrMessage, new MessageContext(addrMessage));
        assertEquals(1, addressHandler.receivedMessages.size());
        
        // When - unregistering handlers
        messageBus.unregisterHandler(serverAddress);
        
        // Then - unregistered handler should not receive messages
        Message newAddrMessage = new Message(clientAddress, serverAddress, MessageType.CLIENT_SET_REQUEST, 
                                           "test2".getBytes(), "other-corr-2");
        messageBus.onMessage(newAddrMessage, new MessageContext(newAddrMessage));
        assertEquals(1, addressHandler.receivedMessages.size()); // Still 1, not 2
    }
    
    @Test
    void shouldThrowExceptionForNullParameters() {
        // Then - should throw exceptions for null parameters
        assertThrows(IllegalArgumentException.class, 
            () -> messageBus.registerHandler((NetworkAddress) null, new TestMessageHandler()));
        
        assertThrows(IllegalArgumentException.class, 
            () -> messageBus.registerHandler(serverAddress, null));
        
        assertThrows(IllegalArgumentException.class, 
            () -> messageBus.registerHandler((String) null, new TestMessageHandler()));
        
        assertThrows(IllegalArgumentException.class, 
            () -> messageBus.registerHandler("test-corr", null));
    }
    
    @Test
    void shouldSupportMessageSendingAndTicking() {
        // Given - a handler registered
        TestMessageHandler handler = new TestMessageHandler();
        messageBus.registerHandler(serverAddress, handler);
        
        // When - sending a message through the MessageBus
        Message message = new Message(clientAddress, serverAddress, MessageType.CLIENT_GET_REQUEST, 
                                    "test-payload".getBytes(), "test-corr");
        messageBus.sendMessage(message);
        
        // And - ticking the network and message bus
        network.tick();
        messageBus.tick();
        
        // Then - handler should receive the message
        assertEquals(1, handler.receivedMessages.size());
        assertEquals(message, handler.receivedMessages.get(0));
    }
    
    // Test helper class
    private static class TestMessageHandler implements MessageHandler {
        public final List<Message> receivedMessages = new ArrayList<>();
        public final List<MessageContext> receivedContexts = new ArrayList<>();
        
        @Override
        public void onMessageReceived(Message message, MessageContext context) {
            receivedMessages.add(message);
            receivedContexts.add(context);
        }
    }
} 