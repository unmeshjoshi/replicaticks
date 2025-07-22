package replicated.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.Message;
import replicated.messaging.MessageType;
import replicated.messaging.NetworkAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimulatedNetwork using callback-based message delivery.
 * These tests verify that the network correctly delivers messages to registered callbacks
 * instead of requiring components to poll via receive() method.
 */
public class SimulatedNetworkTest {
    
    private SimulatedNetwork network;
    private Random seededRandom;
    private TestMessageHandler messageHandler;
    
    @BeforeEach
    void setUp() {
        seededRandom = new Random(12345L); // Fixed seed for deterministic tests
        network = new SimulatedNetwork(seededRandom, 1, 0.0); // 1 tick delay, no packet loss
        messageHandler = new TestMessageHandler();
    }

    @Test
    void shouldSendAndReceiveMessageImmediately() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = Message.networkMessage(source, destination, MessageType.CLIENT_GET_REQUEST, "test".getBytes(), "test-correlation-id");
        
        // When
        network.send(message);
        network.tick(); // Process the message
        
        // Then
        assertEquals(1, messageHandler.receivedMessages.size());
        assertEquals(message, messageHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldReturnEmptyListWhenNoMessages() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        // When - no messages sent
        network.tick();
        
        // Then - no messages should be delivered
        assertTrue(messageHandler.receivedMessages.isEmpty());
    }
    
    @Test
    void shouldDeliverMessagesToCorrectAddress() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress address1 = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress address2 = new NetworkAddress("192.168.1.2", 8080);
        NetworkAddress source = new NetworkAddress("192.168.1.3", 8080);
        
        Message message1 = Message.networkMessage(source, address1, MessageType.CLIENT_GET_REQUEST, "msg1".getBytes(), "test-correlation-id-1");
        Message message2 = Message.networkMessage(source, address2, MessageType.CLIENT_SET_REQUEST, "msg2".getBytes(), "test-correlation-id-2");
        
        // When
        network.send(message1);
        network.send(message2);
        network.tick(); // Process both messages
        
        // Then - both messages should be delivered to the callback
        assertEquals(2, messageHandler.receivedMessages.size());
        assertTrue(messageHandler.receivedMessages.contains(message1));
        assertTrue(messageHandler.receivedMessages.contains(message2));
    }
    
    @Test
    void shouldHandleMultipleMessagesToSameAddress() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        
        Message message1 = Message.networkMessage(source, destination, MessageType.CLIENT_GET_REQUEST, "msg1".getBytes(), "test-correlation-id-3");
        Message message2 = Message.networkMessage(source, destination, MessageType.CLIENT_SET_REQUEST, "msg2".getBytes(), "test-correlation-id-4");
        
        // When
        network.send(message1);
        network.send(message2);
        network.tick(); // Process both messages
        
        // Then
        assertEquals(2, messageHandler.receivedMessages.size());
        assertTrue(messageHandler.receivedMessages.contains(message1));
        assertTrue(messageHandler.receivedMessages.contains(message2));
    }
    
    @Test
    void shouldNotCrashOnNullMessage() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> network.send(null));
    }
    
    @Test
    void shouldSupportConfigurableDelay() {
        // Given - network with 2 tick delay
        SimulatedNetwork delayedNetwork = new SimulatedNetwork(seededRandom, 2, 0.0); // 2 tick delay, no packet loss
        TestMessageHandler delayedHandler = new TestMessageHandler();
        delayedNetwork.registerMessageHandler(delayedHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = Message.networkMessage(source, destination, MessageType.CLIENT_GET_REQUEST, "delayed".getBytes(), "test-correlation-id-5");
        
        // When
        delayedNetwork.send(message);
        
        // Then - message not available immediately
        delayedNetwork.tick();
        assertTrue(delayedHandler.receivedMessages.isEmpty());
        
        // Then - message available after delay
        delayedNetwork.tick(); // Second tick - should deliver now
        assertEquals(1, delayedHandler.receivedMessages.size());
        assertEquals(message, delayedHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldSimulatePacketLoss() {
        // Given - network with 100% packet loss
        SimulatedNetwork lossyNetwork = new SimulatedNetwork(seededRandom, 0, 1.0); // No delay, 100% packet loss
        TestMessageHandler lossyHandler = new TestMessageHandler();
        lossyNetwork.registerMessageHandler(lossyHandler);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = Message.networkMessage(source, destination, MessageType.CLIENT_GET_REQUEST, "lost".getBytes(), "test-correlation-id-6");
        
        // When
        lossyNetwork.send(message);
        lossyNetwork.tick();
        
        // Then - message should be lost
        assertTrue(lossyHandler.receivedMessages.isEmpty());
    }
    
    @Test
    void shouldBeDeterministicWithSameSeeds() {
        // Given - two networks with same seed
        Random seed1 = new Random(42L);
        Random seed2 = new Random(42L);
        SimulatedNetwork network1 = new SimulatedNetwork(seed1, 1, 0.5); // 50% packet loss
        SimulatedNetwork network2 = new SimulatedNetwork(seed2, 1, 0.5); // 50% packet loss
        
        TestMessageHandler handler1 = new TestMessageHandler();
        TestMessageHandler handler2 = new TestMessageHandler();
        network1.registerMessageHandler(handler1);
        network2.registerMessageHandler(handler2);
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        
        // When - send multiple messages to both networks
        for (int i = 0; i < 10; i++) {
            Message msg1 = Message.networkMessage(source, destination, MessageType.CLIENT_GET_REQUEST, ("msg" + i).getBytes(), "test-correlation-id-7-" + i);
            Message msg2 = Message.networkMessage(source, destination, MessageType.CLIENT_GET_REQUEST, ("msg" + i).getBytes(), "test-correlation-id-8-" + i);
            network1.send(msg1);
            network2.send(msg2);
            network1.tick();
            network2.tick();
        }
        
        // Then - both networks should have identical delivery behavior
        assertEquals(handler1.receivedMessages.size(), handler2.receivedMessages.size());
    }
    
    @Test
    void shouldSupportBidirectionalPartitioning() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        Message messageAtoB = Message.networkMessage(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "A->B".getBytes(), "test-correlation-id-9");
        Message messageBtoA = Message.networkMessage(nodeB, nodeA, MessageType.CLIENT_SET_REQUEST, "B->A".getBytes(), "test-correlation-id-10");
        
        // When - partition the link between A and B
        network.partition(nodeA, nodeB);
        
        network.send(messageAtoB);
        network.send(messageBtoA);
        network.tick();
        
        // Then - neither message should be delivered
        assertTrue(messageHandler.receivedMessages.isEmpty());
    }
    
    @Test
    void shouldSupportOneWayPartitioning() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        Message messageAtoB = Message.networkMessage(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "A->B".getBytes(), "test-correlation-id-11");
        Message messageBtoA = Message.networkMessage(nodeB, nodeA, MessageType.CLIENT_SET_REQUEST, "B->A".getBytes(), "test-correlation-id-12");
        
        // When - one-way partition: A can send to B, but B cannot send to A
        network.partitionOneWay(nodeB, nodeA);
        
        network.send(messageAtoB);  // Should work
        network.send(messageBtoA);  // Should be blocked
        network.tick();
        
        // Then - only A->B message should be delivered
        assertEquals(1, messageHandler.receivedMessages.size());
        assertEquals(messageAtoB, messageHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldHealPartitions() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        Message message = Message.networkMessage(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "healed".getBytes(), "test-correlation-id-13");
        
        // When - partition, send message (blocked), then heal partition
        network.partition(nodeA, nodeB);
        network.send(message);
        network.tick();
        
        // Then - message should be blocked
        assertTrue(messageHandler.receivedMessages.isEmpty());
        
        // When - heal partition and send again
        network.healPartition(nodeA, nodeB);
        network.send(message);
        network.tick();
        
        // Then - message should be delivered
        assertEquals(1, messageHandler.receivedMessages.size());
        assertEquals(message, messageHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldSupportPerLinkDelay() {
        // Given - network with per-link delay configuration
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        NetworkAddress nodeC = new NetworkAddress("192.168.1.3", 8080);
        
        // Set different delays for different links
        network.setDelay(nodeA, nodeB, 3); // 3 tick delay
        network.setDelay(nodeA, nodeC, 1); // 1 tick delay
        
        Message messageToB = Message.networkMessage(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "to-B".getBytes(), "test-correlation-id-14");
        Message messageToC = Message.networkMessage(nodeA, nodeC, MessageType.CLIENT_SET_REQUEST, "to-C".getBytes(), "test-correlation-id-15");
        
        // When - send messages simultaneously
        network.send(messageToB);
        network.send(messageToC);
        
        // Then - message to C should arrive first (1 tick delay)
        network.tick();
        assertEquals(1, messageHandler.receivedMessages.size());
        assertEquals(messageToC, messageHandler.receivedMessages.get(0));
        
        // Then - message to B should arrive later (3 tick delay)
        messageHandler.receivedMessages.clear();
        network.tick(); // tick 2
        assertTrue(messageHandler.receivedMessages.isEmpty());
        
        network.tick(); // tick 3
        assertEquals(1, messageHandler.receivedMessages.size());
        assertEquals(messageToB, messageHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldSupportPerLinkPacketLoss() {
        // Given - network with per-link packet loss configuration
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        NetworkAddress nodeC = new NetworkAddress("192.168.1.3", 8080);
        
        // Set different packet loss rates for different links
        network.setPacketLoss(nodeA, nodeB, 1.0); // 100% loss rate
        network.setPacketLoss(nodeA, nodeC, 0.0); // 0% loss rate
        
        Message messageToB = Message.networkMessage(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "to-B".getBytes(), "test-correlation-id-16");
        Message messageToC = Message.networkMessage(nodeA, nodeC, MessageType.CLIENT_SET_REQUEST, "to-C".getBytes(), "test-correlation-id-17");
        
        // When - send messages
        network.send(messageToB);
        network.send(messageToC);
        network.tick();
        
        // Then - only message to C should be delivered (B has 100% loss)
        assertEquals(1, messageHandler.receivedMessages.size());
        assertEquals(messageToC, messageHandler.receivedMessages.get(0));
    }
    
    @Test
    void shouldMaintainPartitionStateAcrossMultipleMessages() {
        // Given - network with registered callback handler
        network.registerMessageHandler(messageHandler);
        
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        // When - establish partition and send multiple messages
        network.partition(nodeA, nodeB);
        
        for (int i = 0; i < 5; i++) {
            Message message = Message.networkMessage(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, ("msg" + i).getBytes(), "test-correlation-id-18-" + i);
            network.send(message);
            network.tick();
        }
        
        // Then - no messages should be delivered due to partition
        assertTrue(messageHandler.receivedMessages.isEmpty());
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