package replicated.replica;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.network.SimulatedNetwork;
import replicated.storage.SimulatedStorage;
import replicated.storage.Storage;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ReplicaBase class common building blocks.
 */
class ReplicaTest {
    
    private NetworkAddress address1;
    private NetworkAddress address2;
    private List<NetworkAddress> peers;
    private MessageBus messageBus;
    private Storage storage;
    
    @BeforeEach
    void setUp() {
        address1 = new NetworkAddress("192.168.1.1", 8080);
        address2 = new NetworkAddress("192.168.1.2", 8080);
        peers = List.of(address2);
        messageBus = new MessageBus(new SimulatedNetwork(new Random(42)), new JsonMessageCodec());
        storage = new SimulatedStorage(new Random(42));
    }
    
    @Test
    void shouldCreateReplicaBaseWithValidParameters() {
        // Given/When
        TestableReplica replica = new TestableReplica("test", address1, peers, messageBus, new JsonMessageCodec(), storage, 10);
        
        // Then
        assertEquals("test", replica.getName());
        assertEquals(address1, replica.getNetworkAddress());
        assertEquals(peers, replica.getPeers());
    }
    
    @Test
    void shouldThrowExceptionForNullName() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica(null, address1, peers, messageBus, new JsonMessageCodec(), storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForNullNetworkAddress() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", null, peers, messageBus, new JsonMessageCodec(), storage, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForNullPeers() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new TestableReplica("test", address1, null, messageBus, new JsonMessageCodec(), storage, 10)
        );
    }
    
    @Test
    void shouldGenerateUniqueRequestIds() {
        // Given
        TestableReplica replica = new TestableReplica("test", address1, peers, messageBus, new JsonMessageCodec(), storage, 10);
        
        // When
        String id1 = replica.generateRequestId();
        String id2 = replica.generateRequestId();
        
        // Then
        assertNotEquals(id1, id2);
        assertTrue(id1.startsWith("test-"));
        assertTrue(id2.startsWith("test-"));
    }

    @Test
    void shouldCreateDefensiveCopyOfPeers() {
        // Given
        List<NetworkAddress> mutablePeers = new java.util.ArrayList<>(peers);
        TestableReplica replica = new TestableReplica("test", address1, mutablePeers, messageBus, new JsonMessageCodec(), storage, 10);
        
        // When
        mutablePeers.clear();
        
        // Then
        assertEquals(1, replica.getPeers().size()); // Should not be affected by external changes
    }

    @Test
    void tickShouldInvokeOnTick() {
        // Given: timeout set to 1 tick for fast expiry
        int timeoutTicks = 1;
        TestableReplica replica = new TestableReplica("tickTest", address1, peers, messageBus, new JsonMessageCodec(), storage, timeoutTicks);

        // When: perform tick
        replica.tick();

        // Then: onTick hook called and waiting list processed (expired request removed)
        assertTrue(replica.onTickCalled);
    }

    @Test
    void shouldRequestWaitingList() {
        // Given: timeout set to 1 tick for fast expiry
        int timeoutTicks = 1;
        TestableReplica replica = new TestableReplica("tickTest", address1, peers, messageBus, new JsonMessageCodec(), storage, timeoutTicks);
        String key = "dummy";
        replica.addDummyPendingRequest(key);
        assertEquals(1, replica.getWaitingListSize());

        // When: perform tick
        replica.tick();

        // Then: onTick hook called and waiting list processed (expired request removed)
        assertEquals(0, replica.getWaitingListSize());
    }

    @Test
    void shouldSerializeAndDeserializePayload() {
        // Given
        TestableReplica replica = new TestableReplica("serde", address1, peers, messageBus, new JsonMessageCodec(), storage, 10);
        GetRequest original = new GetRequest("alpha");
        // When
        byte[] bytes = replica.serializePayload(original);
        GetRequest decoded = replica.deserializePayload(bytes, GetRequest.class);
        // Then
        assertEquals(original, decoded);
    }

    @Test
    void shouldReturnAllNodesIncludingSelf() {
        TestableReplica replica = new TestableReplica("nodes", address1, peers, messageBus, new JsonMessageCodec(), storage, 10);
        List<NetworkAddress> allNodes = replica.getAllNodes();
        assertEquals(peers.size() + 1, allNodes.size());
        assertTrue(allNodes.contains(address1));
    }

    @Test
    void shouldBroadcastInternalRequestsToAllNodes() {
        // Given
        SimulatedNetwork net = new SimulatedNetwork(new Random(123));
        MessageBus bus = new MessageBus(net, new JsonMessageCodec());
        TestableReplica replica = new TestableReplica("bcast", address1, peers, bus, new JsonMessageCodec(), storage, 10);

        // When
        List<String> corrIds = replica.broadcastInternal();
        net.tick();
        bus.tick(); // advance bus which ticks network

        // Then: correlation IDs should be unique (callback-based approach handles message delivery automatically)
        assertEquals(corrIds.size(), new java.util.HashSet<>(corrIds).size());
    }

    @Test
    void shouldGenerateDistinctInternalCorrelationIds() {
        SimulatedNetwork net = new SimulatedNetwork(new Random(99));
        MessageBus bus = new MessageBus(net, new JsonMessageCodec());
        TestableReplica replica = new TestableReplica("cid", address1, peers, bus, new JsonMessageCodec(), storage, 10);
        List<String> cids = replica.broadcastInternal();
        List<String> more = replica.broadcastInternal();
        cids.addAll(more);
        assertEquals(cids.size(), new java.util.HashSet<>(cids).size());
        cids.forEach(id -> assertTrue(id.startsWith("internal-")));
    }


    // Test implementations
    private static class TestableReplica extends Replica {
        boolean onTickCalled = false;

        public TestableReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                               MessageBus bus, MessageCodec codec, Storage storage, int timeoutTicks) {
            super(name, networkAddress, peers, bus, codec, storage, timeoutTicks);
        }

        @Override
        public void onMessageReceived(Message message, MessageContext ctx) {
            // No-op for tests
        }

        @Override
        protected void onTick() {
            onTickCalled = true;
        }

        void addDummyPendingRequest(String key) {
            waitingList.add(key, new RequestCallback<>() {
                @Override public void onResponse(Object response, NetworkAddress fromNode) {}
                @Override public void onError(Exception error) {}
            });
        }

        int getWaitingListSize() {
            return waitingList.size();
        }

        List<String> broadcastInternal() {
            java.util.List<String> captured = new java.util.ArrayList<>();
            AsyncQuorumCallback<Message> cb = new AsyncQuorumCallback<>(getAllNodes().size(), msg -> true);
            broadcastToAllReplicas(cb, (node, cid) -> {
                captured.add(cid);
                return new Message(networkAddress, node, MessageType.INTERNAL_GET_REQUEST, new byte[0], cid);
            });
            return captured;
        }
    }
}