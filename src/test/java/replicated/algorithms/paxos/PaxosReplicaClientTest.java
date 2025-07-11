package replicated.algorithms.paxos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.messaging.NetworkAddress;
import replicated.network.SimulatedNetwork;
import replicated.storage.SimulatedStorage;
import replicated.storage.Storage;
import replicated.network.Network;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for complete Paxos client-replica interaction.
 * Tests the flow: Client -> ProposeRequest -> Paxos Consensus -> Execution -> ProposeResponse
 */
class PaxosReplicaClientTest {

    private PaxosReplica replica1;
    private PaxosReplica replica2; 
    private PaxosReplica replica3;
    private MessageBus messageBus;
    private MessageCodec messageCodec;
    private NetworkAddress clientAddress;
    private Network network;
    private Storage storage1;
    private Storage storage2; 
    private Storage storage3;

    @BeforeEach
    void setUp() {
        // Create simulated network and storage with deterministic random
        Random random = new Random(42L);
        network = new SimulatedNetwork(random);
        storage1 = new SimulatedStorage(new Random(43L));
        storage2 = new SimulatedStorage(new Random(44L));
        storage3 = new SimulatedStorage(new Random(45L));
        
        // Create message bus and codec
        messageCodec = new JsonMessageCodec();
        messageBus = new MessageBus(network, messageCodec);
        
        // Register message bus with network
        network.registerMessageHandler(messageBus);
        
        // Create replica addresses
        NetworkAddress addr1 = new NetworkAddress("127.0.0.1", 9001);
        NetworkAddress addr2 = new NetworkAddress("127.0.0.1", 9002);
        NetworkAddress addr3 = new NetworkAddress("127.0.0.1", 9003);
        clientAddress = new NetworkAddress("127.0.0.1", 8001);
        
        // Create replicas
        replica1 = new PaxosReplica("replica1", addr1, Arrays.asList(addr2, addr3), 
                                   messageBus, messageCodec, storage1);
        replica2 = new PaxosReplica("replica2", addr2, Arrays.asList(addr1, addr3), 
                                   messageBus, messageCodec, storage2);
        replica3 = new PaxosReplica("replica3", addr3, Arrays.asList(addr1, addr2), 
                                   messageBus, messageCodec, storage3);
        
        // Register replicas with message bus
        messageBus.registerHandler(addr1, replica1);
        messageBus.registerHandler(addr2, replica2);
        messageBus.registerHandler(addr3, replica3);
    }

    @Test
    void shouldExecuteIncrementCounterRequestAndReturnResult() throws InterruptedException {
        // Given: A client wants to execute an IncrementCounter command
        byte[] incrementCounterRequest = "INCREMENT_COUNTER:5".getBytes();
        String correlationId = "test-increment-123";
        
        // Set up to capture the response
        AtomicReference<ProposeResponse> capturedResponse = new AtomicReference<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        
        // Register a handler to capture the response
        messageBus.registerHandler(clientAddress, (message, context) -> {
            if (message.messageType() == MessageType.PAXOS_PROPOSE_RESPONSE) {
                ProposeResponse response = messageCodec.decode(message.payload(), ProposeResponse.class);
                capturedResponse.set(response);
                responseLatch.countDown();
            }
        });
        
        // When: Client sends ProposeRequest to replica1
        ProposeRequest request = new ProposeRequest(incrementCounterRequest, correlationId);
        Message message = new Message(
            clientAddress,
            replica1.getNetworkAddress(),
            MessageType.PAXOS_PROPOSE_REQUEST,
            messageCodec.encode(request),
            correlationId
        );
        
        messageBus.sendMessage(message);
        
        // Process the distributed operation by ticking components
        for (int i = 0; i < 20; i++) {
            // Tick storage components first to complete async operations
            storage1.tick();
            storage2.tick();
            storage3.tick();
            
            // Then tick network and message bus
            network.tick();
            messageBus.tick();
        }
        
        // Then: Should receive successful response with execution result
        assertTrue(responseLatch.await(1, TimeUnit.SECONDS), "Should receive response within 1 second");
        
        ProposeResponse response = capturedResponse.get();
        assertNotNull(response, "Should receive a response");
        assertTrue(response.isSuccess(), "Response should indicate success");
        assertNotNull(response.getExecutionResult(), "Should have execution result");
        assertEquals(correlationId, response.getCorrelationId(), "Should have correct correlation ID");
        
        // The execution result should be the result of incrementing counter by 5
        String result = new String(response.getExecutionResult());
        assertTrue(result.contains("5"), "Result should contain the incremented value");
        
        System.out.println("Execution result: " + result);
    }

    @Test
    void shouldHandleFailureWhenConsensusCannotBeReached() throws InterruptedException {
        // This test would verify error handling when consensus fails
        // For now, we'll implement the happy path first
        assertTrue(true, "Placeholder for consensus failure test");
    }
} 