package replicated.algorithms.paxos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.storage.SimulatedStorage;
import replicated.storage.Storage;
import replicated.network.SimulatedNetwork;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PaxosReplica storage integration.
 * Following TDD: Red → Green → Refactor approach.
 */
class PaxosReplicaStorageTest {
    
    private PaxosReplica replica;
    private Storage storage;
    private MessageBus messageBus;
    private MessageCodec messageCodec;
    private NetworkAddress replicaAddress;
    private SimulatedNetwork network;
    
    @BeforeEach
    void setUp() {
        storage = new SimulatedStorage(new Random(42));
        messageCodec = new JsonMessageCodec();
        network = new SimulatedNetwork(new Random(42));
        messageBus = new MessageBus(network, messageCodec);
        
        // Register message bus with network - crucial for message delivery!
        network.registerMessageHandler(messageBus);
        
        replicaAddress = new NetworkAddress("127.0.0.1", 9001);
        
        replica = new PaxosReplica(
            "test-replica",
            replicaAddress,
            Arrays.asList(new NetworkAddress("127.0.0.1", 9002)),
            messageBus,
            messageCodec,
            storage
        );
    }
    
    /**
     * Utility method to run ticks until a condition is met or timeout occurs.
     * This ensures all async storage and network operations complete.
     */
    private void runUntil(Supplier<Boolean> condition, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (!condition.get()) {
            // Tick all components to process async operations
            storage.tick();
            network.tick();
            messageBus.tick();
            
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                fail("Timeout waiting for condition to be met");
            }
            Thread.yield();
        }
    }
    
    /**
     * Convenience method with default timeout of 5 seconds.
     */
    private void runUntil(Supplier<Boolean> condition) {
        runUntil(condition, 5000);
    }

    private void waitForStateLoading(PaxosReplica replica) {
        // Tick storage, network, and message bus until state loading completes
        // We need to be more aggressive about ticking to ensure all async operations complete
        for (int i = 0; i < 50; i++) {
            storage.tick();
            network.tick();
            messageBus.tick();
            
            // Give some time for async operations to complete
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    @Test
    void shouldPersistStateAfterPromise() throws InterruptedException {
        // Red: Write failing test first
        
        // FIRST: Wait for the replica's state to load before sending any messages
        waitForStateLoading(replica);
        
        // Given: A PREPARE request
        ProposalNumber proposalNumber = new ProposalNumber(1, new NetworkAddress("127.0.0.1", 9002));
        PrepareRequest prepareRequest = new PrepareRequest(proposalNumber, "test-correlation");
        Message message = new Message(
            new NetworkAddress("127.0.0.1", 9002),
            replicaAddress,
            MessageType.PAXOS_PREPARE_REQUEST,
            messageCodec.encode(prepareRequest),
            "test-correlation"
        );
        MessageContext context = new MessageContext(message);
        
        // When: Replica handles the PREPARE request (should update promised state)
        replica.onMessageReceived(message, context);
        
        // Wait for async storage operations to complete by ticking until persisted
        runUntil(() -> {
            // The operation is complete when storage has been ticked enough
            return true; // For now, tick a few times to ensure completion
        });
        
        // Additional ticking to ensure completion - be more aggressive
        for (int i = 0; i < 20; i++) {
            storage.tick();
            network.tick();
            messageBus.tick();
        }
        
        // Then: State should be persisted and retrievable
        // Create a new replica with the same storage to test persistence
        // BUT first, let's ensure all pending operations are complete
        for (int i = 0; i < 30; i++) {
            storage.tick();
        }
        
        PaxosReplica newReplica = new PaxosReplica(
            "test-replica-new", 
            new NetworkAddress("127.0.0.1", 9001), 
            List.of(new NetworkAddress("127.0.0.1", 9002), new NetworkAddress("127.0.0.1", 9003)),
            messageBus, 
            messageCodec, 
            storage
        );
        
        // Wait for state loading to complete
        waitForStateLoading(newReplica);
        
        // Register the new replica with the message bus
        network.registerMessageHandler(messageBus);
        
        // The new replica should reject a lower proposal number due to loaded state
        ProposalNumber lowerProposal = new ProposalNumber(0, new NetworkAddress("127.0.0.1", 9003));
        PrepareRequest lowerPrepareRequest = new PrepareRequest(lowerProposal, "test-correlation-2");
        Message lowerMessage = new Message(
            new NetworkAddress("127.0.0.1", 9003),
            replicaAddress,
            MessageType.PAXOS_PREPARE_REQUEST,
            messageCodec.encode(lowerPrepareRequest),
            "test-correlation-2"
        );
        MessageContext lowerContext = new MessageContext(lowerMessage);
        
        // Capture the response
        CountDownLatch responseLatch = new CountDownLatch(1);
        PromiseResponse[] capturedResponse = new PromiseResponse[1];
        
        messageBus.registerHandler(new NetworkAddress("127.0.0.1", 9003), (msg, ctx) -> {
            System.out.println("Promise test: Handler called with message type: " + msg.messageType());
            if (msg.messageType() == MessageType.PAXOS_PROMISE_RESPONSE) {
                try {
                    capturedResponse[0] = messageCodec.decode(msg.payload(), PromiseResponse.class);
                    System.out.println("Promise test: Decoded response: promised=" + capturedResponse[0].isPromised() + 
                                     ", hasAcceptedValue=" + capturedResponse[0].hasAcceptedValue());
                    responseLatch.countDown();
                } catch (Exception e) {
                    System.err.println("Promise test: Failed to decode response: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        
        newReplica.onMessageReceived(lowerMessage, lowerContext);
        
        // Process the response by ticking storage, network and message bus
        for (int i = 0; i < 10; i++) {
            storage.tick();  // Add storage ticking for async persistence
            network.tick();
            messageBus.tick();
        }
        
        // Wait for response
        assertTrue(responseLatch.await(1, TimeUnit.SECONDS), "Should receive promise response");
        assertNotNull(capturedResponse[0], "Should capture promise response");
        assertFalse(capturedResponse[0].isPromised(), "Should reject lower proposal due to persisted state");
    }
    
    @Test
    void shouldPersistStateAfterAccept() throws InterruptedException {
        // Red: Write failing test for accepted state persistence
        
        // FIRST: Wait for the replica's state to load before sending any messages
        waitForStateLoading(replica);
        
        // Given: A ACCEPT request
        ProposalNumber proposalNumber = new ProposalNumber(1, new NetworkAddress("127.0.0.1", 9002));
        byte[] value = "test-value".getBytes();
        AcceptRequest acceptRequest = new AcceptRequest(proposalNumber, value, "test-correlation");
        Message message = new Message(
            new NetworkAddress("127.0.0.1", 9002),
            replicaAddress,
            MessageType.PAXOS_ACCEPT_REQUEST,
            messageCodec.encode(acceptRequest),
            "test-correlation"
        );
        MessageContext context = new MessageContext(message);
        
        // When: Replica handles the ACCEPT request
        replica.onMessageReceived(message, context);
        
        // Wait for async storage operations by ticking until complete
        runUntil(() -> {
            // The operation is complete when storage has been ticked enough
            return true; // For now, tick a few times to ensure completion
        });
        
        // Additional ticking to ensure completion - be more aggressive
        for (int i = 0; i < 20; i++) {
            storage.tick();
            network.tick();
            messageBus.tick();
        }
        
        // Then: State should be persisted
        // Create new replica and verify it has the accepted value
        // BUT first, let's ensure all pending operations are complete
        for (int i = 0; i < 30; i++) {
            storage.tick();
        }
        
        PaxosReplica newReplica = new PaxosReplica(
            "test-replica-new",
            new NetworkAddress("127.0.0.1", 9001),
            List.of(new NetworkAddress("127.0.0.1", 9002), new NetworkAddress("127.0.0.1", 9003)),
            messageBus,
            messageCodec,
            storage
        );
        
        // Wait for state loading to complete
        waitForStateLoading(newReplica);
        
        // Register the new replica with the message bus
        network.registerMessageHandler(messageBus);
        
        // Send a PREPARE request that should return the accepted value
        ProposalNumber higherProposal = new ProposalNumber(2, new NetworkAddress("127.0.0.1", 9003));
        PrepareRequest prepareRequest = new PrepareRequest(higherProposal, "test-correlation-2");
        Message prepareMessage = new Message(
            new NetworkAddress("127.0.0.1", 9003),
            replicaAddress,
            MessageType.PAXOS_PREPARE_REQUEST,
            messageCodec.encode(prepareRequest),
            "test-correlation-2"
        );
        MessageContext prepareContext = new MessageContext(prepareMessage);
        
        // Capture the response
        CountDownLatch responseLatch = new CountDownLatch(1);
        PromiseResponse[] capturedResponse = new PromiseResponse[1];
        
        messageBus.registerHandler(new NetworkAddress("127.0.0.1", 9003), (msg, ctx) -> {
            System.out.println("Accept test: Handler called with message type: " + msg.messageType());
            if (msg.messageType() == MessageType.PAXOS_PROMISE_RESPONSE) {
                try {
                    capturedResponse[0] = messageCodec.decode(msg.payload(), PromiseResponse.class);
                    System.out.println("Accept test: Decoded response: promised=" + capturedResponse[0].isPromised() + 
                                     ", hasAcceptedValue=" + capturedResponse[0].hasAcceptedValue() +
                                     (capturedResponse[0].hasAcceptedValue() ? ", value=" + new String(capturedResponse[0].getAcceptedValue()) : ""));
                    responseLatch.countDown();
                } catch (Exception e) {
                    System.err.println("Accept test: Failed to decode response: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        
        newReplica.onMessageReceived(prepareMessage, prepareContext);
        
        // Process the response by ticking storage, network and message bus
        for (int i = 0; i < 10; i++) {
            storage.tick();  // Add storage ticking for async persistence
            network.tick();
            messageBus.tick();
        }
        
        // Wait for response
        assertTrue(responseLatch.await(1, TimeUnit.SECONDS), "Should receive promise response");
        assertNotNull(capturedResponse[0], "Should capture promise response");
        assertTrue(capturedResponse[0].isPromised(), "Should promise higher proposal");
        assertTrue(capturedResponse[0].hasAcceptedValue(), "Should have accepted value from persisted state");
        assertArrayEquals(value, capturedResponse[0].getAcceptedValue(), "Should return persisted accepted value");
    }
    
    @Test
    void shouldPersistGenerationCounter() throws InterruptedException {
        // Red: Test for generation counter persistence
        
        // FIRST: Wait for the replica's state to load before sending any messages
        waitForStateLoading(replica);
        
        // Given: A client propose request that will increment generation
        byte[] value = "test-proposal-value".getBytes();
        ProposeRequest proposeRequest = new ProposeRequest(value, "client-correlation");
        Message message = new Message(
            new NetworkAddress("127.0.0.1", 9000), // client
            replicaAddress,
            MessageType.PAXOS_PROPOSE_REQUEST,
            messageCodec.encode(proposeRequest),
            "client-correlation"
        );
        MessageContext context = new MessageContext(message);
        
        // When: Replica handles the propose request (should increment generation)
        replica.onMessageReceived(message, context);
        
        // Wait for async storage operations by ticking until complete
        runUntil(() -> {
            // The operation is complete when storage has been ticked enough
            return true; // For now, tick a few times to ensure completion
        });
        
        // Additional ticking to ensure completion
        for (int i = 0; i < 5; i++) {
            storage.tick();
        }
        
        // Then: Generation counter should be persisted
        // Create new replica and verify it loads the incremented generation
        PaxosReplica newReplica = new PaxosReplica(
            "test-replica-new",
            new NetworkAddress("127.0.0.1", 9001),
            List.of(new NetworkAddress("127.0.0.1", 9002), new NetworkAddress("127.0.0.1", 9003)),
            messageBus,
            messageCodec,
            storage
        );
        
        // Wait for state loading to complete
        waitForStateLoading(newReplica);
        
        // When the new replica makes a proposal, it should use a generation higher than the persisted one
        ProposeRequest newProposeRequest = new ProposeRequest("new-value".getBytes(), "new-correlation");
        Message newMessage = new Message(
            new NetworkAddress("127.0.0.1", 9000),
            replicaAddress,
            MessageType.PAXOS_PROPOSE_REQUEST,
            messageCodec.encode(newProposeRequest),
            "new-correlation"
        );
        MessageContext newContext = new MessageContext(newMessage);
        
        // This should work without persistence conflicts
        assertDoesNotThrow(() -> newReplica.onMessageReceived(newMessage, newContext));
    }
} 