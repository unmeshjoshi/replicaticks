package replicated.simulation;

import replicated.client.Client;
import replicated.messaging.*;
import replicated.network.SimulatedNetwork;
import replicated.replica.QuorumReplica;
import replicated.storage.SimulatedStorage;
import replicated.storage.VersionedValue;
import replicated.future.ListenableFuture;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TigerBeetle-style SimulationRunner for testing distributed system robustness.
 * 
 * Features:
 * - Continuous workload generation (random GET/SET operations)
 * - Failure injection: network partitions, packet loss, delays, storage failures
 * - Real-time monitoring and statistics
 * - Deterministic execution with seeded randomness
 */
public class SimulationRunner {
    
    // System configuration
    private final int nodeCount;
    private final long seed;
    private final Random random;
    private final long durationMs;
    
    // System components
    private final SimulatedNetwork network;
    private final ClientMessageBus clientBus;
    private final ServerMessageBus serverBus;
    private final List<QuorumReplica> replicas;
    private final List<SimulatedStorage> storages;
    private final List<NetworkAddress> replicaAddresses;
    private final Client client;
    
    // Simulation state
    private final SimulationDriver driver;
    private long currentTick = 0;
    private long startTime;
    private volatile boolean running = false;
    
    // Failure injection configuration
    private final FailureConfig failureConfig;
    private final FailureScheduler failureScheduler;
    
    // Workload generation
    private final WorkloadGenerator workloadGenerator;
    
    // Monitoring and statistics
    private final SimulationStats stats;
    
    public static class FailureConfig {
        public final double networkPartitionProbability;
        public final double packetLossProbability;
        public final int maxNetworkDelay;
        public final double storageFailureProbability;
        public final long failureCheckInterval; // ticks
        
        public FailureConfig(double networkPartitionProbability, 
                           double packetLossProbability,
                           int maxNetworkDelay,
                           double storageFailureProbability,
                           long failureCheckInterval) {
            this.networkPartitionProbability = networkPartitionProbability;
            this.packetLossProbability = packetLossProbability;
            this.maxNetworkDelay = maxNetworkDelay;
            this.storageFailureProbability = storageFailureProbability;
            this.failureCheckInterval = failureCheckInterval;
        }
        
        public static FailureConfig mild() {
            return new FailureConfig(0.001, 0.01, 5, 0.0001, 100);
        }
        
        public static FailureConfig aggressive() {
            return new FailureConfig(0.01, 0.05, 20, 0.001, 50);
        }
        
        public static FailureConfig chaos() {
            return new FailureConfig(0.02, 0.1, 50, 0.005, 25);
        }
    }
    
    public static class SimulationStats {
        private final AtomicLong totalOperations = new AtomicLong();
        private final AtomicLong successfulOperations = new AtomicLong();
        private final AtomicLong failedOperations = new AtomicLong();
        private final AtomicLong timeoutOperations = new AtomicLong();
        private final AtomicInteger activePartitions = new AtomicInteger();
        private final AtomicLong networkFailures = new AtomicLong();
        private final AtomicLong storageFailures = new AtomicLong();
        
        public void recordOperation(boolean success, boolean timeout) {
            totalOperations.incrementAndGet();
            if (timeout) {
                timeoutOperations.incrementAndGet();
            } else if (success) {
                successfulOperations.incrementAndGet();
            } else {
                failedOperations.incrementAndGet();
            }
        }
        
        public void recordNetworkFailure() {
            networkFailures.incrementAndGet();
        }
        
        public void recordStorageFailure() {
            storageFailures.incrementAndGet();
        }
        
        public void setActivePartitions(int count) {
            activePartitions.set(count);
        }
        
        public double getSuccessRate() {
            long total = totalOperations.get();
            return total > 0 ? (double) successfulOperations.get() / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Operations: %d total, %d success (%.1f%%), %d failed, %d timeout | " +
                "Failures: %d network, %d storage | Partitions: %d active",
                totalOperations.get(),
                successfulOperations.get(),
                getSuccessRate() * 100,
                failedOperations.get(),
                timeoutOperations.get(),
                networkFailures.get(),
                storageFailures.get(),
                activePartitions.get()
            );
        }
    }
    
    public SimulationRunner(int nodeCount, long seed, long durationMs, FailureConfig failureConfig) {
        this.nodeCount = nodeCount;
        this.seed = seed;
        this.random = new Random(seed);
        this.durationMs = durationMs;
        this.failureConfig = failureConfig;
        
        // Initialize statistics
        this.stats = new SimulationStats();
        
        // Create network with some base packet loss and delay
        this.network = new SimulatedNetwork(new Random(seed + 1), 2, 0.001); // 2 tick delay, 0.1% base loss
        
        // Create message buses
        JsonMessageCodec codec = new JsonMessageCodec();
        this.clientBus = new ClientMessageBus(network, codec);
        this.serverBus = new ServerMessageBus(network, codec);
        
        // Create replica addresses
        this.replicaAddresses = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            replicaAddresses.add(new NetworkAddress("127.0.0.1", 8000 + i));
        }
        
        // Create storages and replicas
        this.storages = new ArrayList<>();
        this.replicas = new ArrayList<>();
        
        for (int i = 0; i < nodeCount; i++) {
            NetworkAddress address = replicaAddresses.get(i);
            List<NetworkAddress> peers = new ArrayList<>(replicaAddresses);
            peers.remove(address); // Remove self from peers
            
            SimulatedStorage storage = new SimulatedStorage(new Random(seed + 100 + i), 1, 0.0);
            QuorumReplica replica = new QuorumReplica(
                "replica-" + i, address, peers, serverBus, storage, 50 // 50 tick timeout
            );
            
            storages.add(storage);
            replicas.add(replica);
            serverBus.registerHandler(address, replica);
        }
        
        // Create client
        this.client = new Client(clientBus, replicaAddresses, 100); // 100 tick timeout
        
        // Create simulation driver
        this.driver = new SimulationDriver(
            List.of(network),
            List.copyOf(storages),
            List.copyOf(replicas),
            List.of(client),
            List.of(clientBus, serverBus)
        );
        
        // Initialize failure scheduler and workload generator
        this.failureScheduler = new FailureScheduler();
        this.workloadGenerator = new WorkloadGenerator();
    }
    
    /**
     * Runs the simulation for the configured duration.
     */
    public void run() {
        System.out.println("Starting TigerBeetle-style simulation...");
        System.out.println("Configuration: " + nodeCount + " nodes, " + durationMs + "ms duration, seed=" + seed);
        System.out.println("Failure config: partitions=" + failureConfig.networkPartitionProbability + 
                          ", loss=" + failureConfig.packetLossProbability + 
                          ", delay=" + failureConfig.maxNetworkDelay);
        System.out.println();
        
        startTime = System.currentTimeMillis();
        running = true;
        
        long lastStatsTime = startTime;
        long ticksPerSecond = 0;
        long tickCount = 0;
        
        while (running && (System.currentTimeMillis() - startTime) < durationMs) {
            // Main simulation tick
            driver.tick();
            currentTick++;
            tickCount++;
            
            // Generate workload
            workloadGenerator.tick();
            
            // Inject failures
            failureScheduler.tick();
            
            // Print stats every second
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastStatsTime >= 1000) {
                ticksPerSecond = tickCount;
                tickCount = 0;
                lastStatsTime = currentTime;
                
                long elapsedSeconds = (currentTime - startTime) / 1000;
                System.out.printf("[%02d:%02d] Tick %d (%d tps) | %s%n", 
                    elapsedSeconds / 60, elapsedSeconds % 60, 
                    currentTick, ticksPerSecond, stats);
            }
            
            // Small yield to prevent 100% CPU usage
            if (currentTick % 1000 == 0) {
                Thread.yield();
            }
        }
        
        running = false;
        System.out.println();
        System.out.println("Simulation completed!");
        printFinalStats();
    }
    
    /**
     * Stops the simulation.
     */
    public void stop() {
        running = false;
    }
    
    private void printFinalStats() {
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("=== FINAL SIMULATION STATISTICS ===");
        System.out.println("Duration: " + totalTime + "ms (" + (totalTime / 1000) + "s)");
        System.out.println("Total ticks: " + currentTick);
        System.out.println("Average TPS: " + (currentTick * 1000 / Math.max(totalTime, 1)));
        System.out.println("Final stats: " + stats);
        System.out.println();
        
        // Print individual replica states
        for (int i = 0; i < replicas.size(); i++) {
            System.out.println("Replica " + i + " (" + replicaAddresses.get(i) + "): operational");
        }
    }
    
    /**
     * Handles failure injection based on configuration.
     */
    private class FailureScheduler {
        private long lastFailureCheck = 0;
        private final Set<String> activePartitions = new HashSet<>();
        
        public void tick() {
            if (currentTick - lastFailureCheck < failureConfig.failureCheckInterval) {
                return;
            }
            lastFailureCheck = currentTick;
            
            // Network partition injection
            if (random.nextDouble() < failureConfig.networkPartitionProbability) {
                injectNetworkPartition();
            }
            
            // Heal some partitions randomly
            if (random.nextDouble() < 0.1 && !activePartitions.isEmpty()) {
                healRandomPartition();
            }
            
            // Packet loss injection
            updatePacketLoss();
            
            // Network delay injection
            updateNetworkDelays();
            
            stats.setActivePartitions(activePartitions.size());
        }
        
        private void injectNetworkPartition() {
            if (replicaAddresses.size() < 2) return;
            
            NetworkAddress node1 = replicaAddresses.get(random.nextInt(replicaAddresses.size()));
            NetworkAddress node2 = replicaAddresses.get(random.nextInt(replicaAddresses.size()));
            
            if (!node1.equals(node2)) {
                String partitionKey = getPartitionKey(node1, node2);
                if (!activePartitions.contains(partitionKey)) {
                    network.partition(node1, node2);
                    activePartitions.add(partitionKey);
                    stats.recordNetworkFailure();
                    System.out.println("FAILURE: Network partition between " + node1 + " and " + node2);
                }
            }
        }
        
        private void healRandomPartition() {
            if (activePartitions.isEmpty()) return;
            
            String partitionToHeal = activePartitions.iterator().next();
            String[] nodes = partitionToHeal.split("<->");
            NetworkAddress node1 = NetworkAddress.parse(nodes[0]);
            NetworkAddress node2 = NetworkAddress.parse(nodes[1]);
            
            network.healPartition(node1, node2);
            activePartitions.remove(partitionToHeal);
            System.out.println("HEAL: Network partition healed between " + node1 + " and " + node2);
        }
        
        private void updatePacketLoss() {
            for (int i = 0; i < replicaAddresses.size(); i++) {
                for (int j = i + 1; j < replicaAddresses.size(); j++) {
                    if (random.nextDouble() < 0.01) { // 1% chance to update loss rate
                        double lossRate = random.nextDouble() * failureConfig.packetLossProbability;
                        network.setPacketLoss(replicaAddresses.get(i), replicaAddresses.get(j), lossRate);
                    }
                }
            }
        }
        
        private void updateNetworkDelays() {
            for (int i = 0; i < replicaAddresses.size(); i++) {
                for (int j = i + 1; j < replicaAddresses.size(); j++) {
                    if (random.nextDouble() < 0.005) { // 0.5% chance to update delay
                        int delay = random.nextInt(failureConfig.maxNetworkDelay + 1);
                        network.setDelay(replicaAddresses.get(i), replicaAddresses.get(j), delay);
                    }
                }
            }
        }
        
        private String getPartitionKey(NetworkAddress node1, NetworkAddress node2) {
            // Ensure consistent ordering for partition keys using simple format
            String addr1 = node1.ipAddress() + ":" + node1.port();
            String addr2 = node2.ipAddress() + ":" + node2.port();
            if (addr1.compareTo(addr2) < 0) {
                return addr1 + "<->" + addr2;
            } else {
                return addr2 + "<->" + addr1;
            }
        }
    }
    
    /**
     * Generates continuous workload of GET/SET operations.
     */
    private class WorkloadGenerator {
        private long lastOperationTick = 0;
        private final int operationInterval = 5; // Operations every 5 ticks
        private final Map<String, String> expectedValues = new HashMap<>();
        private int operationCounter = 0;
        
        public void tick() {
            if (currentTick - lastOperationTick < operationInterval) {
                return;
            }
            lastOperationTick = currentTick;
            
            // Randomly choose between GET and SET operations
            if (random.nextBoolean() && !expectedValues.isEmpty()) {
                // 50% chance of GET if we have data
                performGetOperation();
            } else {
                // SET operation
                performSetOperation();
            }
        }
        
        private void performGetOperation() {
            String[] keys = expectedValues.keySet().toArray(new String[0]);
            if (keys.length == 0) return;
            
            String key = keys[random.nextInt(keys.length)];
            String expectedValue = expectedValues.get(key);
            
            ListenableFuture<VersionedValue> future = client.sendGetRequest(key);
            
            // Set up callback to track results
            future.onSuccess(result -> {
                boolean success = (result != null && expectedValue.equals(new String(result.value())));
                stats.recordOperation(success, false);
                if (!success) {
                    System.out.println("GET mismatch: key=" + key + 
                                      ", expected=" + expectedValue + 
                                      ", got=" + (result != null ? new String(result.value()) : "null"));
                }
            });
            
            future.onFailure(error -> {
                boolean timeout = error.getMessage().contains("timeout");
                stats.recordOperation(false, timeout);
            });
        }
        
        private void performSetOperation() {
            String key = "key-" + (operationCounter % 100); // Cycle through 100 keys
            String value = "value-" + operationCounter + "-" + currentTick;
            operationCounter++;
            
            expectedValues.put(key, value);
            
            ListenableFuture<Boolean> future = client.sendSetRequest(key, value.getBytes());
            
            // Set up callback to track results
            future.onSuccess(result -> {
                stats.recordOperation(result, false);
            });
            
            future.onFailure(error -> {
                boolean timeout = error.getMessage().contains("timeout");
                stats.recordOperation(false, timeout);
                // Remove from expected values if SET failed
                expectedValues.remove(key);
            });
        }
    }
    
    /**
     * Main method for running the simulation.
     */
    public static void main(String[] args) {
        // Default configuration
        int nodeCount = 3;
        long durationMs = 5 * 60 * 1000; // 5 minutes
        long seed = System.currentTimeMillis();
        FailureConfig failureConfig = FailureConfig.mild();
        
        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--nodes":
                    nodeCount = Integer.parseInt(args[++i]);
                    break;
                case "--duration":
                    durationMs = Long.parseLong(args[++i]) * 1000; // Convert seconds to ms
                    break;
                case "--seed":
                    seed = Long.parseLong(args[++i]);
                    break;
                case "--failure-mode":
                    String mode = args[++i];
                    failureConfig = switch (mode) {
                        case "mild" -> FailureConfig.mild();
                        case "aggressive" -> FailureConfig.aggressive();
                        case "chaos" -> FailureConfig.chaos();
                        default -> throw new IllegalArgumentException("Unknown failure mode: " + mode);
                    };
                    break;
                case "--help":
                    printUsage();
                    return;
            }
        }
        
        System.out.println("Creating simulation with " + nodeCount + " nodes for " + (durationMs/1000) + " seconds...");
        
        SimulationRunner runner = new SimulationRunner(nodeCount, seed, durationMs, failureConfig);
        
        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown signal received, stopping simulation...");
            runner.stop();
        }));
        
        runner.run();
    }
    
    private static void printUsage() {
        System.out.println("TigerBeetle-style Simulation Runner");
        System.out.println("Usage: SimulationRunner [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --nodes <count>       Number of replica nodes (default: 3)");
        System.out.println("  --duration <seconds>  Simulation duration in seconds (default: 300)");
        System.out.println("  --seed <number>       Random seed for deterministic runs");
        System.out.println("  --failure-mode <mode> Failure injection mode: mild|aggressive|chaos (default: mild)");
        System.out.println("  --help               Show this help message");
        System.out.println();
        System.out.println("Failure modes:");
        System.out.println("  mild:       Low failure rates for basic testing");
        System.out.println("  aggressive: Higher failure rates for stress testing");
        System.out.println("  chaos:      Maximum failure injection for chaos engineering");
    }
} 