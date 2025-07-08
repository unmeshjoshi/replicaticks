package replicated.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.client.Client;
import replicated.future.ListenableFuture;
import replicated.messaging.*;
import replicated.network.Network;
import replicated.replica.QuorumBasedReplica;
import replicated.storage.Storage;
import replicated.storage.VersionedValue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for SimulationDriver to verify tick orchestration and TestUtils.runUntil functionality.
 */
class TestSimulationDriverTest {
    
    private SimulationDriver driver;
    private TestNetwork network;
    private TestStorage storage;
    private QuorumBasedReplica replica;
    private Client client;
    private NetworkAddress replicaAddress;
    
    @BeforeEach
    void setUp() {
        // Setup test components
        replicaAddress = new NetworkAddress("127.0.0.1", 8080);
        network = new TestNetwork();
        storage = new TestStorage();
        
        // Create replica and client
        replica = new QuorumBasedReplica("test-replica", replicaAddress, List.of(), 
                                        new ServerMessageBus(network, new JsonMessageCodec()), storage, 10);
        client = new Client(new ClientMessageBus(network, new JsonMessageCodec()));
        
        // Create driver with test components
        driver = new SimulationDriver(
            List.of(network),
            List.of(storage),
            List.of(replica),
            List.of(client),
            List.of() // No message buses needed for this test
        );
    }
    
    @Test
    void shouldTickAllComponents() {
        // Given - components start with 0 ticks
        assertEquals(0, network.getTickCount());
        assertEquals(0, storage.getTickCount());
        // Note: Client and Replica don't expose tick counts, but they are ticked
        
        // When - tick the driver
        driver.tick();
        
        // Then - all components should be ticked once
        assertEquals(1, network.getTickCount());
        assertEquals(1, storage.getTickCount());
        // Note: Client and Replica don't expose tick counts, but they are ticked
    }
    
    @Test
    void shouldRunUntilConditionIsMet() {
        // Given - a condition that becomes true after 5 ticks
        AtomicInteger tickCount = new AtomicInteger(0);
        
        // When - run until condition is met
        TestUtils.runUntil(() -> tickCount.incrementAndGet() > 5, 100000, driver);
        
        // Then - condition should be met and components should be ticked 5 times
        assertEquals(6, tickCount.get());
        assertEquals(5, network.getTickCount());
        assertEquals(5, storage.getTickCount());
    }
    
    @Test
    void shouldTimeoutWhenConditionNeverMet() {
        // Given - a condition that never becomes true
        Supplier<Boolean> neverTrue = () -> false;
        
        // When & Then - should timeout
        assertThrows(RuntimeException.class, () -> {
            TestUtils.runUntil(neverTrue, 100, driver); // 100ms timeout
        });
    }
    
    @Test
    void shouldRunSimulationForSpecifiedTicks() {
        // Given - components start with 0 ticks
        assertEquals(0, network.getTickCount());
        assertEquals(0, storage.getTickCount());
        // Note: Client and Replica don't expose tick counts, but they are ticked
        
        // When - run simulation for 10 ticks
        driver.runSimulation(10);
        
        // Then - all components should be ticked 10 times
        assertEquals(10, network.getTickCount());
        assertEquals(10, storage.getTickCount());
        // Note: Client and Replica don't expose tick counts, but they are ticked
    }
    
    // Test helper classes
    
    private static class TestNetwork implements Network {
        private int tickCount = 0;
        
        @Override
        public void send(Message message) {
            // No-op for testing
        }
        
        @Override
        public List<Message> receive(NetworkAddress address) {
            return List.of();
        }
        
        @Override
        public void tick() {
            tickCount++;
        }
        
        public int getTickCount() {
            return tickCount;
        }
        
        // Other required methods with no-op implementations
        @Override
        public NetworkAddress establishConnection(NetworkAddress destination) {
            return new NetworkAddress("127.0.0.1", 50000);
        }
        
        @Override
        public void partition(NetworkAddress source, NetworkAddress destination) {}
        
        @Override
        public void partitionOneWay(NetworkAddress source, NetworkAddress destination) {}
        
        @Override
        public void healPartition(NetworkAddress source, NetworkAddress destination) {}
        
        @Override
        public void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks) {}
        
        @Override
        public void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate) {}
    }
    
    private static class TestStorage implements Storage {
        private int tickCount = 0;
        
        @Override
        public ListenableFuture<VersionedValue> get(byte[] key) {
            return new ListenableFuture<>();
        }
        
        @Override
        public ListenableFuture<Boolean> set(byte[] key, VersionedValue value) {
            return new ListenableFuture<>();
        }
        
        @Override
        public void tick() {
            tickCount++;
        }
        
        public int getTickCount() {
            return tickCount;
        }
    }
} 