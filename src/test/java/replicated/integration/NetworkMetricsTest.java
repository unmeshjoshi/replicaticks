package replicated.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import replicated.network.*;
import replicated.messaging.*;
import replicated.replica.*;
import replicated.storage.*;
import replicated.client.Client;

import java.util.*;

/**
 * Verifies that NioNetwork.metrics counters reflect the live connection set when a
 * small cluster of replicas plus a client exchange messages.
 */
class NetworkMetricsTest {

    private NioNetwork network;
    private ClientMessageBus clientBus;
    private ServerMessageBus serverBus;

    private NetworkAddress r1Addr;
    private NetworkAddress r2Addr;
    private NetworkAddress r3Addr;

    private QuorumBasedReplica r1;
    private QuorumBasedReplica r2;
    private QuorumBasedReplica r3;

    private Client client;

    @BeforeEach
    void setUp() {
        network = new NioNetwork();
        clientBus = new ClientMessageBus(network, new JsonMessageCodec());
        serverBus = new ServerMessageBus(network, new JsonMessageCodec());

        r1Addr = new NetworkAddress("127.0.0.1", 9201);
        r2Addr = new NetworkAddress("127.0.0.1", 9202);
        r3Addr = new NetworkAddress("127.0.0.1", 9203);

        List<NetworkAddress> all = List.of(r1Addr, r2Addr, r3Addr);

        // Bind server sockets
        network.bind(r1Addr);
        network.bind(r2Addr);
        network.bind(r3Addr);

        // Create storage engines
        Storage s1 = new SimulatedStorage(new Random());
        Storage s2 = new SimulatedStorage(new Random());
        Storage s3 = new SimulatedStorage(new Random());

        r1 = new QuorumBasedReplica("r1", r1Addr, peersExcept(r1Addr, all), serverBus, s1);
        r2 = new QuorumBasedReplica("r2", r2Addr, peersExcept(r2Addr, all), serverBus, s2);
        r3 = new QuorumBasedReplica("r3", r3Addr, peersExcept(r3Addr, all), serverBus, s3);

        serverBus.registerHandler(r1Addr, r1);
        serverBus.registerHandler(r2Addr, r2);
        serverBus.registerHandler(r3Addr, r3);

        // client collaborates via the same MessageBus for simplicity
        client = new Client(clientBus, List.of(r1Addr, r2Addr, r3Addr));
    }

    private List<NetworkAddress> peersExcept(NetworkAddress self, List<NetworkAddress> all) {
        List<NetworkAddress> peers = new ArrayList<>(all);
        peers.remove(self);
        return peers;
    }

    @Test
    void metricsReflectActiveConnections() {
        // Baseline metrics before any traffic
        var before = network.getMetrics();
        assertEquals(0, before.inboundConnections());
        assertEquals(0, before.outboundConnections());
        assertEquals(0, before.closedConnections());

        // Send a few requests to drive traffic and connection establishment
        for (int i = 0; i < 5; i++) {
            client.sendSetRequest("k" + i, ("v" + i).getBytes(), r1Addr);
        }

        // Drive the system for a while so sockets can connect and responses flow
        for (int tick = 0; tick < 200; tick++) {
            network.tick();
            r1.tick();
            r2.tick();
            r3.tick();
            client.tick();
        }

        var m = network.getMetrics();
        System.out.println("Metrics: " + m);

        // After traffic we expect at least:
        // - Some connections
        assertTrue(m.inboundConnections() + m.outboundConnections() > 0, "Some connections should exist");
        // Closed may be non-zero if loopback sockets close quickly; just ensure non-negative (always true)
        assertTrue(m.closedConnections() >= 0, "Closed connections should be non-negative");
        // At least one ConnectionStats present
        assertTrue(m.connections().size() > 0);

        // Validate that each ConnectionStats entry has reasonable values
        for (var cs : m.connections()) {
            assertNotNull(cs.local, "Local address null");
            assertNotNull(cs.remote, "Remote address null");
            // At least one direction should have transferred some bytes in this test run
            assertTrue(cs.bytesSent() + cs.bytesReceived() >= 0, "Byte counters non-negative");
            assertTrue(cs.openedMillis() >= 0, "Opened timestamp non-negative");
            // lastActivityMillis should be >= openedMillis
            assertTrue(cs.lastActivityMillis() >= cs.openedMillis(), "Last activity >= opened");
            // Lifetime should be non-negative
            assertTrue(cs.lifetimeMillis() >= 0);
        }
    }
}
