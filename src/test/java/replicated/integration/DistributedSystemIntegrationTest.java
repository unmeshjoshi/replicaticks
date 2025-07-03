package replicated.integration;

import replicated.messaging.*;
import replicated.network.*;
import replicated.storage.*;
import replicated.future.ListenableFuture;
import replicated.replica.QuorumBasedReplica;
import replicated.client.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive integration tests for distributed system scenarios.
 * Tests end-to-end functionality, partition tolerance, failure recovery,
 * read/write quorums, and conflict resolution.
 */
class DistributedSystemIntegrationTest {
    
    private Network network;
    private MessageBus messageBus;
    private List<QuorumBasedReplica> replicas;
    private List<Client> clients;
    private List<NetworkAddress> replicaAddresses;
    private Random random;
    
    @BeforeEach
    void setUp() {
        // Setup deterministic environment - use a fixed seed for each test to ensure isolation
        // This prevents random state contamination between test runs
        random = new Random(42L);
        network = new SimulatedNetwork(random);
        messageBus = new MessageBus(network, new JsonMessageCodec());
        
        // Setup replica addresses
        replicaAddresses = List.of(
            new NetworkAddress("192.168.1.1", 8080),
            new NetworkAddress("192.168.1.2", 8080), 
            new NetworkAddress("192.168.1.3", 8080),
            new NetworkAddress("192.168.1.4", 8080),
            new NetworkAddress("192.168.1.5", 8080)
        );
        
        // Create replicas with individual storage - use predictable seeds for each replica
        replicas = new ArrayList<>();
        for (int i = 0; i < replicaAddresses.size(); i++) {
            NetworkAddress address = replicaAddresses.get(i);
            List<NetworkAddress> peers = replicaAddresses.stream()
                .filter(addr -> !addr.equals(address))
                .toList();
            
            // Use a fixed seed for each replica to ensure test isolation
            Storage storage = new SimulatedStorage(new Random(42L + i));
            QuorumBasedReplica replica = new QuorumBasedReplica("replica-" + address.port(), address, peers, messageBus, storage);
            replicas.add(replica);
            
            // Register replica with message bus
            messageBus.registerHandler(address, replica);
        }
        
        // Create clients (addresses will be auto-assigned)
        clients = new ArrayList<>();
        for (int i = 0; i < 2; i++) { // Create 2 clients
            Client client = new Client(messageBus);
            clients.add(client);
            // Client handler is auto-registered by MessageBus.sendClientMessage()
        }
    }
    
    @AfterEach
    void tearDown() {
        // Ensure complete test isolation by clearing all system state
        // This prevents pending operations from one test affecting subsequent tests
        
        // 1. Heal all possible partitions to ensure clean network state
        for (int i = 0; i < replicaAddresses.size(); i++) {
            for (int j = i + 1; j < replicaAddresses.size(); j++) {
                network.healPartition(replicaAddresses.get(i), replicaAddresses.get(j));
            }
        }
        
        // 2. Clear all message bus handlers to ensure no message routing contamination
        for (NetworkAddress address : replicaAddresses) {
            messageBus.unregisterHandler(address);
        }
        
        // 3. Force re-initialization of all components to clear pending state
        // This ensures that pending operations from previous tests don't interfere
        // Note: We don't recreate the entire system here since that's done in @BeforeEach
        // but we ensure that all handlers are properly re-registered
        for (int i = 0; i < replicas.size(); i++) {
            messageBus.registerHandler(replicaAddresses.get(i), replicas.get(i));
        }
        
        // NOTE: Removed messageBus.tick() call as it was unnecessarily advancing tick counts
        // Fresh instances are created in @BeforeEach, so no need to process messages here
        
        System.out.println("Debug: tearDown completed - system state cleaned");
    }
    
    @Test
    void shouldPerformEndToEndGetSetOperation() {
        // Given - A distributed system with 5 replicas
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        Client client = clients.get(0);
        NetworkAddress coordinatorReplica = replicaAddresses.get(0);
        
        AtomicReference<Boolean> setResult = new AtomicReference<>();
        AtomicReference<VersionedValue> getResult = new AtomicReference<>();
        
        // When - Client performs SET operation
        ListenableFuture<Boolean> setFuture = client.sendSetRequest(key, value, coordinatorReplica);
        setFuture.onSuccess(setResult::set);
        
        // Process the distributed operation
        processDistributedOperation(10); // Allow time for quorum
        
        // Then - SET should succeed
        assertTrue(setResult.get(), "SET operation should succeed with quorum");
        
        // When - Client performs GET operation
        ListenableFuture<VersionedValue> getFuture = client.sendGetRequest(key, coordinatorReplica);
        getFuture.onSuccess(getResult::set);
        
        // Process the distributed operation
        processDistributedOperation(10);
        
        // Then - GET should return the stored value
        assertNotNull(getResult.get(), "GET operation should return a value");
        assertArrayEquals(value, getResult.get().value(), "Retrieved value should match stored value");
    }
    
    @Test
    void shouldHandleNetworkPartition() {
        // Given - A distributed system with 5 replicas
        String key = "partition:test";
        byte[] value = "partition value".getBytes();
        Client client = clients.get(0);
        
        // When - Create a partition isolating 2 replicas from 3 replicas
        NetworkAddress isolatedReplica1 = replicaAddresses.get(0);
        NetworkAddress isolatedReplica2 = replicaAddresses.get(1);
        NetworkAddress majorityReplica = replicaAddresses.get(2);
        
        // Partition the network (isolate first 2 replicas)
        for (int i = 0; i < 2; i++) {
            for (int j = 2; j < 5; j++) {
                network.partition(replicaAddresses.get(i), replicaAddresses.get(j));
            }
        }
        
        // Try to write to the majority partition (should succeed)
        AtomicReference<Boolean> majoritySetResult = new AtomicReference<>();
        ListenableFuture<Boolean> majoritySetFuture = client.sendSetRequest(key, value, majorityReplica);
        majoritySetFuture.onSuccess(majoritySetResult::set);
        
        processDistributedOperation(20); // Allow extra time for partition
        
        // Then - Write to majority partition should succeed
        assertTrue(majoritySetResult.get(), "Write to majority partition should succeed");
        
        // When - Try to write to minority partition (should fail or timeout)
        AtomicReference<Boolean> minoritySetResult = new AtomicReference<>();
        AtomicReference<Throwable> minoritySetError = new AtomicReference<>();
        
        ListenableFuture<Boolean> minoritySetFuture = client.sendSetRequest(key + ":minority", value, isolatedReplica1);
        minoritySetFuture.onSuccess(minoritySetResult::set);
        minoritySetFuture.onFailure(minoritySetError::set);
        
        processDistributedOperation(20);
        
        // Then - Write to minority partition should fail (timeout or explicit failure)
        assertTrue(minoritySetResult.get() == null || !minoritySetResult.get() || minoritySetError.get() != null, 
                  "Write to minority partition should fail");
    }
    
    @Test
    void shouldHandleReplicaFailure() {
        // Given - A distributed system with 5 replicas
        String key = "failure:test";
        byte[] value = "failure value".getBytes();
        Client client = clients.get(0);
        
        // When - Simulate replica failure by removing it from message bus
        NetworkAddress failedReplica = replicaAddresses.get(0);
        messageBus.unregisterHandler(failedReplica);
        
        // Try to perform operations through remaining replicas
        NetworkAddress workingReplica = replicaAddresses.get(1);
        AtomicReference<Boolean> setResult = new AtomicReference<>();
        ListenableFuture<Boolean> setFuture = client.sendSetRequest(key, value, workingReplica);
        setFuture.onSuccess(setResult::set);
        
        processDistributedOperation(20); // Allow extra time for failure handling
        
        // Then - Operation should still succeed with remaining replicas
        assertTrue(setResult.get(), "Operation should succeed despite replica failure");
        
        // When - Try to read the value
        AtomicReference<VersionedValue> getResult = new AtomicReference<>();
        ListenableFuture<VersionedValue> getFuture = client.sendGetRequest(key, workingReplica);
        getFuture.onSuccess(getResult::set);
        
        processDistributedOperation(20);
        
        // Then - Should be able to read the value
        assertNotNull(getResult.get(), "Should be able to read value despite replica failure");
        assertArrayEquals(value, getResult.get().value(), "Retrieved value should be correct");
    }
    
    @Test
    void shouldHandleConcurrentOperations() {
        // Given - Multiple clients performing concurrent operations
        String baseKey = "concurrent:test:";
        byte[] value = "concurrent value".getBytes();
        
        List<AtomicReference<Boolean>> setResults = new ArrayList<>();
        List<ListenableFuture<Boolean>> setFutures = new ArrayList<>();
        
        // When - Multiple clients write different keys concurrently
        for (int i = 0; i < 10; i++) {
            String key = baseKey + i;
            Client client = clients.get(i % clients.size());
            NetworkAddress replica = replicaAddresses.get(i % replicaAddresses.size());
            
            AtomicReference<Boolean> result = new AtomicReference<>();
            setResults.add(result);
            
            ListenableFuture<Boolean> future = client.sendSetRequest(key, value, replica);
            future.onSuccess(result::set);
            setFutures.add(future);
        }
        
        // Process all concurrent operations
        processDistributedOperation(30);
        
        // Then - All operations should eventually succeed
        for (AtomicReference<Boolean> result : setResults) {
            assertTrue(result.get(), "Concurrent operation should succeed");
        }
    }
    
    @Test
    void shouldDemonstrateQuorumRequirements() {
        // Given - A 5-replica system (quorum = 3)
        String key = "quorum:test";
        byte[] value = "quorum value".getBytes();
        Client client = clients.get(0);
        
        // When - Partition network so only 2 replicas can communicate (less than quorum)
        // Isolate 3 replicas from the other 2
        for (int i = 0; i < 3; i++) {
            for (int j = 3; j < 5; j++) {
                network.partition(replicaAddresses.get(i), replicaAddresses.get(j));
            }
        }
        
        // Try to write to minority partition (2 replicas - insufficient for quorum)
        AtomicReference<Boolean> minorityResult = new AtomicReference<>();
        AtomicReference<Throwable> minorityError = new AtomicReference<>();
        
        ListenableFuture<Boolean> minorityFuture = client.sendSetRequest(key, value, replicaAddresses.get(3));
        minorityFuture.onSuccess(minorityResult::set);
        minorityFuture.onFailure(minorityError::set);
        
        processDistributedOperation(25); // Allow time for timeout
        
        // Then - Minority partition should fail to achieve quorum
        assertTrue(minorityResult.get() == null || !minorityResult.get() || minorityError.get() != null,
                  "Minority partition should fail to achieve quorum");
        
        // When - Write to majority partition (3 replicas - sufficient for quorum)
        AtomicReference<Boolean> majorityResult = new AtomicReference<>();
        AtomicReference<Throwable> majorityError = new AtomicReference<>();
        
        System.out.println("Debug: Starting majority partition write to " + replicaAddresses.get(0));
        ListenableFuture<Boolean> majorityFuture = client.sendSetRequest(key, value, replicaAddresses.get(0));
        majorityFuture.onSuccess(result -> {
            System.out.println("Debug: Majority operation succeeded with result: " + result);
            majorityResult.set(result);
        });
        majorityFuture.onFailure(error -> {
            System.out.println("Debug: Majority operation failed with error: " + error.getMessage());
            majorityError.set(error);
        });
        
        System.out.println("Debug: Processing distributed operation for 20 ticks...");
        processDistributedOperation(20);
        System.out.println("Debug: Finished processing distributed operation");
        
        // Then - Majority partition should succeed
        if (majorityError.get() != null) {
            fail("Majority partition operation failed with error: " + majorityError.get().getMessage());
        }
        
        // Debug output to understand the failure
        System.out.println("Debug: majorityResult.get() = " + majorityResult.get());
        System.out.println("Debug: majorityError.get() = " + majorityError.get());
        
        assertNotNull(majorityResult.get(), "Majority partition should complete successfully");
        assertTrue(majorityResult.get(), "Majority partition should achieve quorum");
    }
    
    @Test
    void shouldHandleConflictResolution() {
        // Given - A partitioned network with conflicting writes
        String key = "conflict:test";
        byte[] value1 = "value from partition 1".getBytes();
        byte[] value2 = "value from partition 2".getBytes();
        Client client = clients.get(0);
        
        // Create a partition: 3 replicas vs 2 replicas
        for (int i = 0; i < 3; i++) {
            for (int j = 3; j < 5; j++) {
                network.partition(replicaAddresses.get(i), replicaAddresses.get(j));
            }
        }
        
        // When - Write to majority partition (should succeed)
        AtomicReference<Boolean> majorityResult = new AtomicReference<>();
        ListenableFuture<Boolean> majorityFuture = client.sendSetRequest(key, value1, replicaAddresses.get(0));
        majorityFuture.onSuccess(majorityResult::set);
        
        processDistributedOperation(20);
        
        // Then - Majority write should succeed
        assertTrue(majorityResult.get(), "Majority partition write should succeed");
        
        // When - Heal the partition
        for (int i = 0; i < 3; i++) {
            for (int j = 3; j < 5; j++) {
                network.healPartition(replicaAddresses.get(i), replicaAddresses.get(j));
            }
        }
        
        // Allow time for partition healing
        processDistributedOperation(30);
        
        // Then - Should be able to read consistent value from any replica
        AtomicReference<VersionedValue> getResult = new AtomicReference<>();
        ListenableFuture<VersionedValue> getFuture = client.sendGetRequest(key, replicaAddresses.get(4));
        getFuture.onSuccess(getResult::set);
        
        processDistributedOperation(20);
        
        // The system should maintain consistency (latest timestamped value should win)
        assertNotNull(getResult.get(), "Should be able to read value after partition healing");
        assertArrayEquals(value1, getResult.get().value(), "Should read the value from majority partition");
    }
    
    @Test
    void shouldDemonstrateEventualConsistency() {
        // Given - A distributed system with eventual consistency
        String key = "eventual:test";
        byte[] initialValue = "initial value".getBytes();
        byte[] updatedValue = "updated value".getBytes();
        Client client = clients.get(0);
        
        // When - Write initial value to system
        AtomicReference<Boolean> initialWriteResult = new AtomicReference<>();
        ListenableFuture<Boolean> initialWriteFuture = client.sendSetRequest(key, initialValue, replicaAddresses.get(0));
        initialWriteFuture.onSuccess(initialWriteResult::set);
        
        processDistributedOperation(15);
        
        // Then - Initial write should succeed
        assertNotNull(initialWriteResult.get(), "Initial write should complete");
        assertTrue(initialWriteResult.get(), "Initial write should succeed");
        
        // When - Create temporary partition
        network.partition(replicaAddresses.get(0), replicaAddresses.get(4));
        network.partition(replicaAddresses.get(1), replicaAddresses.get(4));
        network.partition(replicaAddresses.get(2), replicaAddresses.get(4));
        
        // Write updated value to majority partition
        AtomicReference<Boolean> updateResult = new AtomicReference<>();
        ListenableFuture<Boolean> updateFuture = client.sendSetRequest(key, updatedValue, replicaAddresses.get(0));
        updateFuture.onSuccess(updateResult::set);
        
        processDistributedOperation(20);
        
        // Then - Update should succeed in majority partition
        assertTrue(updateResult.get(), "Update should succeed in majority partition");
        
        // When - Heal the partition to allow synchronization
        network.healPartition(replicaAddresses.get(0), replicaAddresses.get(4));
        network.healPartition(replicaAddresses.get(1), replicaAddresses.get(4));
        network.healPartition(replicaAddresses.get(2), replicaAddresses.get(4));
        
        // Allow time for eventual consistency
        processDistributedOperation(30);
        
        // Then - All replicas should eventually have the same value
        AtomicReference<VersionedValue> replicaResult = new AtomicReference<>();
        ListenableFuture<VersionedValue> replicaFuture = client.sendGetRequest(key, replicaAddresses.get(4));
        replicaFuture.onSuccess(replicaResult::set);
        
        processDistributedOperation(20);
        
        // The previously isolated replica should now have the updated value
        assertNotNull(replicaResult.get(), "Should be able to read value from previously isolated replica");
        assertArrayEquals(updatedValue, replicaResult.get().value(), "All replicas should eventually converge to same value");
    }
    
    @Test
    void shouldHandleReadRepairScenario() {
        // Given - A system with stale data on some replicas
        String key = "read:repair";
        byte[] staleValue = "stale value".getBytes();
        byte[] freshValue = "fresh value".getBytes();
        Client client = clients.get(0);
        
        // When - Write stale value with network partition
        network.partition(replicaAddresses.get(3), replicaAddresses.get(0));
        network.partition(replicaAddresses.get(4), replicaAddresses.get(0));
        
        AtomicReference<Boolean> staleWriteResult = new AtomicReference<>();
        ListenableFuture<Boolean> staleWriteFuture = client.sendSetRequest(key, staleValue, replicaAddresses.get(0));
        staleWriteFuture.onSuccess(staleWriteResult::set);
        
        processDistributedOperation(20);
        
        // Then - Stale write should succeed in majority partition
        assertTrue(staleWriteResult.get(), "Stale write should succeed");
        
        // When - Heal partition and write fresh value
        network.healPartition(replicaAddresses.get(3), replicaAddresses.get(0));
        network.healPartition(replicaAddresses.get(4), replicaAddresses.get(0));
        
        // Small delay to allow healing
        processDistributedOperation(5);
        
        AtomicReference<Boolean> freshWriteResult = new AtomicReference<>();
        ListenableFuture<Boolean> freshWriteFuture = client.sendSetRequest(key, freshValue, replicaAddresses.get(0));
        freshWriteFuture.onSuccess(freshWriteResult::set);
        
        processDistributedOperation(20);
        
        // Then - Fresh write should succeed
        assertTrue(freshWriteResult.get(), "Fresh write should succeed");
        
        // When - Read from different replicas
        AtomicReference<VersionedValue> readResult = new AtomicReference<>();
        ListenableFuture<VersionedValue> readFuture = client.sendGetRequest(key, replicaAddresses.get(3));
        readFuture.onSuccess(readResult::set);
        
        processDistributedOperation(20);
        
        // Then - Should read the latest value (read repair should work)
        assertNotNull(readResult.get(), "Should be able to read value");
        assertArrayEquals(freshValue, readResult.get().value(), "Should read latest value due to read repair");
    }
    
    // Helper method to process distributed operations through multiple ticks
    private void processDistributedOperation(int ticks) {
        System.out.println("Debug: Starting processDistributedOperation for " + ticks + " ticks");
        
        for (int currentTick = 1; currentTick <= ticks; currentTick++) {
            // Process all components in correct order
            System.out.println("Debug: Processing tick " + currentTick + "/" + ticks);
            
            // 1. Process clients and replicas (application layer)
            final long tickValue = currentTick;
            clients.forEach(client -> client.tick(tickValue));
            replicas.forEach(replica -> replica.tick(tickValue));
            
            // 2. Process message bus routing (includes network.tick())
            messageBus.tick();
            
            // Debug: Check message queue states
            if (currentTick % 5 == 0) { // Log every 5 ticks to avoid spam
                System.out.println("Debug: Tick " + currentTick + " - checking system state");
                for (int i = 0; i < replicaAddresses.size(); i++) {
                    NetworkAddress addr = replicaAddresses.get(i);
                    List<Message> messages = network.receive(addr);
                    if (!messages.isEmpty()) {
                        System.out.println("Debug: Replica " + addr + " has " + messages.size() + " unprocessed messages");
                    }
                }
            }
            
            // Small delay to allow async operations to complete
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        System.out.println("Debug: Completed processDistributedOperation");
    }
} 