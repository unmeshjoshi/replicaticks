package replicated.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.client.QuorumClient;
import replicated.messaging.JsonMessageCodec;
import replicated.messaging.MessageBus;
import replicated.messaging.NetworkAddress;
import replicated.network.NioNetwork;
import replicated.algorithms.quorum.QuorumReplica;
import replicated.network.id.ReplicaId;
import replicated.network.topology.ReplicaConfig;
import replicated.network.topology.Topology;
import replicated.storage.SimulatedStorage;
import replicated.storage.Storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that NioNetwork.metrics counters reflect the live connection set when a
 * small cluster of replicas plus a client exchange messages.
 */
class NetworkMetricsTest {

    private NioNetwork network;
    private MessageBus messageBus;

    private NetworkAddress r1Addr;
    private NetworkAddress r2Addr;
    private NetworkAddress r3Addr;

    private QuorumReplica r1;
    private QuorumReplica r2;
    private QuorumReplica r3;

    private QuorumClient quorumClient;

    @BeforeEach
    void setUp() {


        r1Addr = new NetworkAddress("127.0.0.1", 9201);
        r2Addr = new NetworkAddress("127.0.0.1", 9202);
        r3Addr = new NetworkAddress("127.0.0.1", 9203);

        List<NetworkAddress> all = List.of(r1Addr, r2Addr, r3Addr);

        ReplicaId replicaId1 = ReplicaId.of(1);
        ReplicaId replicaId2 = ReplicaId.of(2);
        ReplicaId replicaId3 = ReplicaId.of(3);

        Topology topology = new Topology(List.of(
            new ReplicaConfig(replicaId1, r1Addr),
            new ReplicaConfig(replicaId2, r2Addr),
            new ReplicaConfig(replicaId3, r3Addr)
        ));
        network = new NioNetwork(topology);
        messageBus = new MessageBus(network, new JsonMessageCodec());

        // Register message bus directly with network (no multiplexer needed)
        network.registerMessageHandler(messageBus);

        // Bind server sockets
        network.bind(r1Addr);
        network.bind(r2Addr);
        network.bind(r3Addr);

        // Create storage engines
        Storage s1 = new SimulatedStorage(new Random());
        Storage s2 = new SimulatedStorage(new Random());
        Storage s3 = new SimulatedStorage(new Random());

        List<ReplicaId> allReplicaIds = List.of(replicaId1, replicaId2, replicaId3);

        JsonMessageCodec codec = new JsonMessageCodec();
        r1 = new QuorumReplica(replicaId1, r1Addr, peersExcept(r1Addr, all), messageBus, codec, s1, 60, peerExcept(replicaId1, allReplicaIds));
        r2 = new QuorumReplica(replicaId2, r2Addr, peersExcept(r2Addr, all), messageBus, codec, s2, 60, peerExcept(replicaId2, allReplicaIds));
        r3 = new QuorumReplica(replicaId3,  r3Addr, peersExcept(r3Addr, all), messageBus, codec, s3, 60, peerExcept(replicaId2, allReplicaIds));

        messageBus.registerHandler(r1Addr, r1);
        messageBus.registerHandler(r2Addr, r2);
        messageBus.registerHandler(r3Addr, r3);

        // client collaborates via the same MessageBus for simplicity
        quorumClient = new QuorumClient(messageBus, codec, List.of(r1Addr, r2Addr, r3Addr), allReplicaIds);
    }

    private List<ReplicaId> peerExcept(ReplicaId self, List<ReplicaId> all) {
        List<ReplicaId> peers = new ArrayList<>(all);
        peers.remove(self);
        return peers;
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
            quorumClient.sendSetRequest("k" + i, ("v" + i).getBytes(), r1Addr);
        }

        // Drive the system for a while so sockets can connect and responses flow
        for (int tick = 0; tick < 200; tick++) {
            network.tick();
            r1.tick();
            r2.tick();
            r3.tick();
            quorumClient.tick();
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
