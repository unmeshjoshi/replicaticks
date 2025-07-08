package replicated.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.Message;
import replicated.messaging.MessageType;
import replicated.messaging.NetworkAddress;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedNetworkTest {

    private SimulatedNetwork network;
    private Random seededRandom;
    
    @BeforeEach
    void setUp() {
        seededRandom = new Random(12345L); // Fixed seed for deterministic tests
        network = new SimulatedNetwork(seededRandom);
    }
    
    @Test
    void shouldSendAndReceiveMessageImmediately() {
        // Given - SimulatedNetwork with no delays
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, "test".getBytes(), "test-correlation-id");
        
        // When
        network.send(message);
        network.tick(); // Process the message
        List<Message> receivedMessages = network.receive(destination);
        
        // Then
        assertEquals(1, receivedMessages.size());
        assertEquals(message, receivedMessages.get(0));
    }
    
    @Test
    void shouldReturnEmptyListWhenNoMessages() {
        // Given
        NetworkAddress address = new NetworkAddress("192.168.1.1", 8080);
        
        // When
        List<Message> messages = network.receive(address);
        
        // Then
        assertTrue(messages.isEmpty());
    }
    
    @Test
    void shouldDeliverMessagesToCorrectAddress() {
        // Given
        NetworkAddress address1 = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress address2 = new NetworkAddress("192.168.1.2", 8080);
        NetworkAddress source = new NetworkAddress("192.168.1.3", 8080);
        
        Message message1 = new Message(source, address1, MessageType.CLIENT_GET_REQUEST, "msg1".getBytes(), "test-correlation-id-1");
        Message message2 = new Message(source, address2, MessageType.CLIENT_SET_REQUEST, "msg2".getBytes(), "test-correlation-id-2");
        
        // When
        network.send(message1);
        network.send(message2);
        network.tick(); // Process both messages
        
        // Then
        List<Message> messages1 = network.receive(address1);
        List<Message> messages2 = network.receive(address2);
        
        assertEquals(1, messages1.size());
        assertEquals(message1, messages1.get(0));
        assertEquals(1, messages2.size());
        assertEquals(message2, messages2.get(0));
    }
    
    @Test
    void shouldHandleMultipleMessagesToSameAddress() {
        // Given
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        
        Message message1 = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, "msg1".getBytes(), "test-correlation-id-3");
        Message message2 = new Message(source, destination, MessageType.CLIENT_SET_REQUEST, "msg2".getBytes(), "test-correlation-id-4");
        
        // When
        network.send(message1);
        network.send(message2);
        network.tick(); // Process both messages
        List<Message> receivedMessages = network.receive(destination);
        
        // Then
        assertEquals(2, receivedMessages.size());
        assertTrue(receivedMessages.contains(message1));
        assertTrue(receivedMessages.contains(message2));
    }
    
    @Test
    void shouldNotCrashOnNullMessage() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> network.send(null));
    }
    
    @Test
    void shouldNotCrashOnNullAddress() {
        // When & Then  
        assertThrows(IllegalArgumentException.class, () -> network.receive(null));
    }
    
    @Test
    void shouldSupportConfigurableDelay() {
        // Given - network with 2 tick delay
        SimulatedNetwork delayedNetwork = new SimulatedNetwork(seededRandom, 2, 0.0); // 2 tick delay, no packet loss
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, "delayed".getBytes(), "test-correlation-id-5");
        
        // When
        delayedNetwork.send(message);
        
        // Then - message not available immediately
        delayedNetwork.tick();
        assertTrue(delayedNetwork.receive(destination).isEmpty());
        
        // Then - message available after delay
        delayedNetwork.tick(); // Second tick - should deliver now
        List<Message> receivedMessages = delayedNetwork.receive(destination);
        assertEquals(1, receivedMessages.size());
        assertEquals(message, receivedMessages.get(0));
    }
    
    @Test
    void shouldSimulatePacketLoss() {
        // Given - network with 100% packet loss
        SimulatedNetwork lossyNetwork = new SimulatedNetwork(seededRandom, 0, 1.0); // No delay, 100% packet loss
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        Message message = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, "lost".getBytes(), "test-correlation-id-6");
        
        // When
        lossyNetwork.send(message);
        lossyNetwork.tick();
        
        // Then - message should be lost
        List<Message> receivedMessages = lossyNetwork.receive(destination);
        assertTrue(receivedMessages.isEmpty());
    }
    
    @Test
    void shouldBeDeterministicWithSameSeeds() {
        // Given - two networks with same seed
        Random seed1 = new Random(42L);
        Random seed2 = new Random(42L);
        SimulatedNetwork network1 = new SimulatedNetwork(seed1, 1, 0.5); // 50% packet loss
        SimulatedNetwork network2 = new SimulatedNetwork(seed2, 1, 0.5); // 50% packet loss
        
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        
        // When - send multiple messages to both networks
        for (int i = 0; i < 10; i++) {
            Message msg1 = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, ("msg" + i).getBytes(), "test-correlation-id-7-" + i);
            Message msg2 = new Message(source, destination, MessageType.CLIENT_GET_REQUEST, ("msg" + i).getBytes(), "test-correlation-id-8-" + i);
            network1.send(msg1);
            network2.send(msg2);
            network1.tick();
            network2.tick();
        }
        
        // Then - both networks should have identical delivery behavior
        List<Message> received1 = network1.receive(destination);
        List<Message> received2 = network2.receive(destination);
        assertEquals(received1.size(), received2.size());
    }
    
    @Test
    void shouldSupportBidirectionalPartitioning() {
        // Given
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        Message messageAtoB = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "A->B".getBytes(), "test-correlation-id-9");
        Message messageBtoA = new Message(nodeB, nodeA, MessageType.CLIENT_SET_REQUEST, "B->A".getBytes(), "test-correlation-id-10");
        
        // When - partition the link between A and B
        network.partition(nodeA, nodeB);
        
        network.send(messageAtoB);
        network.send(messageBtoA);
        network.tick();
        
        // Then - neither message should be delivered
        assertTrue(network.receive(nodeB).isEmpty());
        assertTrue(network.receive(nodeA).isEmpty());
    }
    
    @Test
    void shouldSupportOneWayPartitioning() {
        // Given
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        Message messageAtoB = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "A->B".getBytes(), "test-correlation-id-11");
        Message messageBtoA = new Message(nodeB, nodeA, MessageType.CLIENT_SET_REQUEST, "B->A".getBytes(), "test-correlation-id-12");
        
        // When - one-way partition: A can send to B, but B cannot send to A
        network.partitionOneWay(nodeB, nodeA);
        
        network.send(messageAtoB);  // Should work
        network.send(messageBtoA);  // Should be blocked
        network.tick();
        
        // Then
        List<Message> receivedAtB = network.receive(nodeB);
        List<Message> receivedAtA = network.receive(nodeA);
        
        assertEquals(1, receivedAtB.size());  // A->B message delivered
        assertEquals(messageAtoB, receivedAtB.get(0));
        assertTrue(receivedAtA.isEmpty());    // B->A message blocked
    }
    
    @Test
    void shouldHealPartitions() {
        // Given
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        // When - create and then heal partition
        network.partition(nodeA, nodeB);
        network.healPartition(nodeA, nodeB);
        
        Message message = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "test".getBytes(), "test-correlation-id-13");
        network.send(message);
        network.tick();
        
        // Then - message should be delivered (partition healed)
        List<Message> received = network.receive(nodeB);
        assertEquals(1, received.size());
        assertEquals(message, received.get(0));
    }
    
    // TODO: Fix timing issue with per-link delays 
    // @Test 
    // void shouldSupportPerLinkDelays() { ... }
    
    @Test
    void shouldSupportPerLinkPacketLoss() {
        // Given
        Random deterministicRandom = new Random(999L); // Specific seed for predictable loss
        SimulatedNetwork testNetwork = new SimulatedNetwork(deterministicRandom);
        
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        NetworkAddress nodeC = new NetworkAddress("192.168.1.3", 8080);
        
        // When - set different packet loss rates
        testNetwork.setPacketLoss(nodeA, nodeB, 1.0); // 100% loss A->B
        testNetwork.setPacketLoss(nodeA, nodeC, 0.0); // 0% loss A->C
        
        Message messageToB = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, "to-B".getBytes(), "test-correlation-id-14");
        Message messageToC = new Message(nodeA, nodeC, MessageType.CLIENT_SET_REQUEST, "to-C".getBytes(), "test-correlation-id-15");
        
        testNetwork.send(messageToB);
        testNetwork.send(messageToC);
        testNetwork.tick();
        
        // Then
        assertTrue(testNetwork.receive(nodeB).isEmpty()); // Lost due to 100% loss rate
        assertEquals(1, testNetwork.receive(nodeC).size()); // Delivered due to 0% loss rate
    }
    
    @Test
    void shouldMaintainPartitionStateAcrossMultipleMessages() {
        // Given
        NetworkAddress nodeA = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress nodeB = new NetworkAddress("192.168.1.2", 8080);
        
        network.partition(nodeA, nodeB);
        
        // When - send multiple messages over time
        for (int i = 0; i < 5; i++) {
            Message message = new Message(nodeA, nodeB, MessageType.CLIENT_GET_REQUEST, ("msg" + i).getBytes(), "test-correlation-id-16-" + i);
            network.send(message);
            network.tick();
        }
        
        // Then - all messages should be blocked
        assertTrue(network.receive(nodeB).isEmpty());
    }
} 