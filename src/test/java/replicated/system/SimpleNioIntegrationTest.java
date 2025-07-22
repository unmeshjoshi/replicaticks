package replicated.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.network.NioNetwork;
import replicated.network.id.ReplicaId;
import replicated.network.topology.ReplicaConfig;
import replicated.network.topology.Topology;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple integration test to verify NIO network messaging works correctly.
 * This helps isolate networking issues from the full quorum logic.
 */
class SimpleNioIntegrationTest {
    
    private NioNetwork network;
    private MessageCodec codec;
    private MessageBus messageBus;

    private ReplicaId replicaId1;
    private ReplicaId replicaId2;
    private NetworkAddress address1;
    private NetworkAddress address2;
    
    private TestMessageHandler handler1;
    private TestMessageHandler handler2;
    
    @BeforeEach
    void setUp() {
        replicaId1 = ReplicaId.of(1);
        replicaId2 = ReplicaId.of(2);
        // Setup network addresses
        address1 = new NetworkAddress("127.0.0.1", 9001);
        address2 = new NetworkAddress("127.0.0.1", 9002);
        
        // Setup network and messaging
        Topology topology = new Topology(List.of(
            new ReplicaConfig(replicaId1, address1), new ReplicaConfig(replicaId2, address2)));

        network = new NioNetwork(topology);
        codec = new JsonMessageCodec();
        messageBus = new MessageBus(network, codec);
        
        // Register message bus directly with network (no multiplexer needed)
        network.registerMessageHandler(messageBus);
        
        // Bind network addresses
        network.bind(address1);
        network.bind(address2);
        
        // Setup message handlers
        handler1 = new TestMessageHandler();
        handler2 = new TestMessageHandler();
        
        // Register message handlers
        messageBus.registerHandler(address1, handler1);
        messageBus.registerHandler(address2, handler2);
        
        // Allow time for network setup
        runUntil(() -> true, 100);
    }
    
    @AfterEach
    void tearDown() {
        if (network != null) network.close();
    }
    
    /**
     * Utility method to run ticks until a condition is met or timeout occurs.
     */
    private void runUntil(Supplier<Boolean> condition, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (!condition.get()) {
            messageBus.tick();
            network.tick();
            
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                fail("Timeout waiting for condition to be met");
            }
            
            Thread.yield();
        }
    }
    
    private void runUntil(Supplier<Boolean> condition) {
        runUntil(condition, 5000);
    }
    
    @Test
    void shouldSendSimpleMessage() {
        // Given
        String testPayload = "test-message";
        Message message = Message.networkMessage(address1, address2, MessageType.CLIENT_GET_REQUEST, testPayload.getBytes(), "test-correlation-id");
        
        // When
        messageBus.sendMessage(message);
        
        // Wait for message to be received
        runUntil(() -> handler2.receivedMessage.get() != null);
        
        // Then
        Message received = handler2.receivedMessage.get();
        assertNotNull(received);
        assertEquals(address1, received.source());
        assertEquals(address2, received.destination());
        assertEquals(MessageType.CLIENT_GET_REQUEST, received.messageType());
        assertArrayEquals(testPayload.getBytes(), received.payload());
    }
    
    @Test
    void shouldSendMessageInBothDirections() {
        // Given
        String payload1to2 = "message-1-to-2";
        String payload2to1 = "message-2-to-1";
        
        Message message1to2 = Message.networkMessage(address1, address2, MessageType.CLIENT_GET_REQUEST, payload1to2.getBytes(), "test-correlation-id-1");
        Message message2to1 = Message.networkMessage(address2, address1, MessageType.CLIENT_SET_REQUEST, payload2to1.getBytes(), "test-correlation-id-2");
        
        // When
        messageBus.sendMessage(message1to2);
        messageBus.sendMessage(message2to1);
        
        // Wait for both messages to be received
        runUntil(() -> handler1.receivedMessage.get() != null && handler2.receivedMessage.get() != null);
        
        // Then
        Message received1 = handler2.receivedMessage.get(); // handler2 receives from address1
        Message received2 = handler1.receivedMessage.get(); // handler1 receives from address2
        
        assertNotNull(received1);
        assertArrayEquals(payload1to2.getBytes(), received1.payload());
        
        assertNotNull(received2);
        assertArrayEquals(payload2to1.getBytes(), received2.payload());
    }
    
    @Test
    void shouldHandleMultipleMessages() {
        // Given
        int numMessages = 3;
        
        System.out.println("Starting multiple messages test...");
        System.out.println("Network type: " + network.getClass().getSimpleName());
        System.out.println("MessageBus type: " + messageBus.getClass().getSimpleName());
        
        // When - send multiple messages
        for (int i = 0; i < numMessages; i++) {
            String payload = "message-" + i;
            Message message = Message.networkMessage(address1, address2, MessageType.CLIENT_GET_REQUEST, payload.getBytes(), "test-correlation-id-3");
            System.out.println("About to send message " + i + " via MessageBus: " + payload);
            messageBus.sendMessage(message);
            System.out.println("MessageBus.sendMessage() completed for message " + i);
        }
        
        System.out.println("All messages sent via MessageBus, waiting for reception...");
        
        // Wait for all messages to be received
        runUntil(() -> {
            int currentCount = handler2.messageCount.get();
            System.out.println("Current message count: " + currentCount + " / " + numMessages);
            return currentCount >= numMessages;
        });
        
        // Then
        System.out.println("Final message count: " + handler2.messageCount.get());
        assertTrue(handler2.messageCount.get() >= numMessages);
    }
    
    /**
     * Simple test message handler that records received messages.
     */
    private static class TestMessageHandler implements MessageHandler {
        final AtomicReference<Message> receivedMessage = new AtomicReference<>();
        final AtomicReference<Integer> messageCount = new AtomicReference<>(0);
        
        @Override
        public void onMessageReceived(Message message, MessageContext ctx) {
            receivedMessage.set(message);
            messageCount.set(messageCount.get() + 1);
        }
    }
} 