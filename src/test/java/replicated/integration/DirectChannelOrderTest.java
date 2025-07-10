package replicated.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.client.Client;
import replicated.future.ListenableFuture;
import replicated.messaging.JsonMessageCodec;
import replicated.messaging.MessageBus;
import replicated.messaging.NetworkAddress;
import replicated.network.MessageContext;
import replicated.network.SimulatedNetwork;
import replicated.replica.QuorumReplica;
import replicated.simulation.SimulationDriver;
import replicated.storage.SimulatedStorage;
import replicated.storage.VersionedValue;

import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that two sequential requests from the same client receive responses
 * on the same simulated channel and in the same order.
 */
public class DirectChannelOrderTest {
    private SimulatedNetwork network;
    private MessageBus messageBus;
    private List<QuorumReplica> replicas;
    private NetworkAddress replicaAddr;
    private SimulationDriver simulationDriver;
    private Client client;

    @BeforeEach
    void setup() {
        network = new SimulatedNetwork(new Random(1));
        JsonMessageCodec codec = new JsonMessageCodec();
        messageBus = new MessageBus(network, codec);
        
        // Register message bus directly with network (no multiplexer needed)
        network.registerMessageHandler(messageBus);
        
        replicaAddr = new NetworkAddress("10.0.0.1", 7000);
        client = new Client(messageBus, codec, List.of(replicaAddr));
        SimulatedStorage storage = new SimulatedStorage(new Random());
        QuorumReplica replica = new QuorumReplica("r1", replicaAddr, List.of(), messageBus, codec, storage);
        messageBus.registerHandler(replicaAddr, replica);
        replicas = List.of(replica);
        
        // Create SimulationDriver to orchestrate all component ticking
        simulationDriver = new SimulationDriver(
            List.of(network),
            List.of(storage),
            replicas.stream().map(r -> (replicated.replica.Replica) r).toList(),
            List.of(client),
            List.of(messageBus)
        );
    }

    @Test
    void shouldPreserveSocketAndOrder() {
        // Note: simulationDriver is already created in setup() with the correct storage instance
        // No need to recreate it here

        // send first request
        ListenableFuture<Boolean> f1 = client.sendSetRequest("k", "v1".getBytes(), replicaAddr);
        // advance ticks to process
        // Use SimulationDriver to orchestrate all component ticking
        simulationDriver.runSimulation(100);

        assertTrue(f1.isCompleted() && f1.getResult());

        // Capture context after first response
        MessageContext ctx1 = network.getContextFor(network.getLastDeliveredMessage());
        assertNotNull(ctx1);

        // second request
        ListenableFuture<VersionedValue> f2 = client.sendGetRequest("k", replicaAddr);
        // Use SimulationDriver to orchestrate all component ticking
        simulationDriver.runSimulation(10);
        assertEquals("v1", new String(Objects.requireNonNull(f2.getResult()).value()));

        MessageContext ctx2 = network.getContextFor(network.getLastDeliveredMessage());
        assertNotNull(ctx2);
        assertSame(ctx1.getSourceChannel(), ctx2.getSourceChannel(), "responses should use same channel");
    }

}
