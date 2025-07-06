package replicated.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.client.Client;
import replicated.messaging.*;
import replicated.network.*;
import replicated.replica.QuorumBasedReplica;
import replicated.storage.SimulatedStorage;
import replicated.storage.VersionedValue;
import replicated.future.ListenableFuture;
import replicated.util.DebugConfig;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that two sequential requests from the same client receive responses
 * on the same simulated channel and in the same order.
 */
public class DirectChannelOrderTest {
    private SimulatedNetwork network;
    private MessageBus bus;
    private List<QuorumBasedReplica> replicas;
    private NetworkAddress replicaAddr;

    @BeforeEach
    void setup() {
        network = new SimulatedNetwork(new Random(1));
        bus = new MessageBus(network, new JsonMessageCodec());
        replicaAddr = new NetworkAddress("10.0.0.1", 7000);
        QuorumBasedReplica replica = new QuorumBasedReplica("r1", replicaAddr, List.of(), bus, new SimulatedStorage(new Random()));
        bus.registerHandler(replicaAddr, replica);
        replicas = List.of(replica);
    }

    @Test
    void shouldPreserveSocketAndOrder() {
        Client client = new Client(bus);

        // send first request
        ListenableFuture<Boolean> f1 = client.sendSetRequest("k", "v1".getBytes(), replicaAddr);
        // advance ticks to process
        processTicks(10);
        assertTrue(f1.isCompleted() && f1.getResult());

        // Capture context after first response
        MessageContext ctx1 = network.getContextFor(network.getLastDeliveredMessage());
        assertNotNull(ctx1);

        // second request
        ListenableFuture<VersionedValue> f2 = client.sendGetRequest("k", replicaAddr);
        processTicks(10);
        assertEquals("v1", new String(Objects.requireNonNull(f2.getResult()).value()));

        MessageContext ctx2 = network.getContextFor(network.getLastDeliveredMessage());
        assertNotNull(ctx2);
        assertSame(ctx1.getSourceChannel(), ctx2.getSourceChannel(), "responses should use same channel");
    }

    private void processTicks(int n) {
        long tick = 1;
        for (int i = 0; i < n; i++, tick++) {
            for (QuorumBasedReplica r : replicas) r.tick(tick);
            bus.tick();
            network.tick();
        }
    }
}
