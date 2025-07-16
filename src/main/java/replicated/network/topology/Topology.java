package replicated.network.topology;

import replicated.network.id.ReplicaId;
import java.util.*;

/**
 * Immutable cluster topology containing configuration for all replicas.
 * Provides index-based lookup of replica configurations using ReplicaId.
 */
public class Topology {
    
    private final List<ReplicaConfig> replicas;
    private final Map<ReplicaId, ReplicaConfig> replicaMap;
    
    /**
     * Creates a new Topology from a list of replica configurations.
     * 
     * @param replicas the list of replica configurations (must not be null or empty)
     * @throws NullPointerException if replicas is null
     * @throws IllegalArgumentException if replicas is empty or contains duplicates
     */
    public Topology(List<ReplicaConfig> replicas) {
        Objects.requireNonNull(replicas, "Replicas list cannot be null");
        
        if (replicas.isEmpty()) {
            throw new IllegalArgumentException("Replicas list cannot be empty");
        }
        
        // Validate no duplicate replica IDs
        Set<ReplicaId> seenIds = new HashSet<>();
        Set<String> seenAddresses = new HashSet<>();
        
        for (ReplicaConfig config : replicas) {
            if (!seenIds.add(config.replicaId())) {
                throw new IllegalArgumentException("Duplicate replica ID: " + config.replicaId());
            }
            
            String internodeAddr = config.internodeAddressString();
            String clientAddr = config.clientRpcAddressString();
            
            if (!seenAddresses.add(internodeAddr)) {
                throw new IllegalArgumentException("Duplicate internode address: " + internodeAddr);
            }
            
            if (!seenAddresses.add(clientAddr)) {
                throw new IllegalArgumentException("Duplicate client RPC address: " + clientAddr);
            }
        }
        
        // Create immutable copies
        this.replicas = List.copyOf(replicas);
        
        // Build lookup map
        Map<ReplicaId, ReplicaConfig> map = new HashMap<>();
        for (ReplicaConfig config : replicas) {
            map.put(config.replicaId(), config);
        }
        this.replicaMap = Map.copyOf(map);
    }
    
    /**
     * Gets the replica configuration by ReplicaId.
     * 
     * @param replicaId the replica identifier
     * @return the replica configuration
     * @throws IllegalArgumentException if replica ID is not found
     */
    public ReplicaConfig get(ReplicaId replicaId) {
        ReplicaConfig config = replicaMap.get(replicaId);
        if (config == null) {
            throw new IllegalArgumentException("Replica not found: " + replicaId);
        }
        return config;
    }
    
    /**
     * Gets the replica configuration by index.
     * This is the primary lookup method that works with ReplicaId.index().
     * 
     * @param index the replica index
     * @return the replica configuration
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public ReplicaConfig get(int index) {
        if (index < 0 || index >= replicas.size()) {
            throw new IndexOutOfBoundsException("Invalid replica index: " + index + ", size: " + replicas.size());
        }
        return replicas.get(index);
    }
    
    /**
     * Checks if a replica with the given ID exists in the topology.
     * 
     * @param replicaId the replica identifier
     * @return true if the replica exists, false otherwise
     */
    public boolean contains(ReplicaId replicaId) {
        return replicaMap.containsKey(replicaId);
    }
    
    /**
     * Returns the number of replicas in the topology.
     * 
     * @return the replica count
     */
    public int size() {
        return replicas.size();
    }
    
    /**
     * Returns all replica configurations in the topology.
     * 
     * @return an immutable list of replica configurations
     */
    public List<ReplicaConfig> getAllReplicas() {
        return replicas;
    }
    
    /**
     * Returns all replica IDs in the topology.
     * 
     * @return an immutable set of replica IDs
     */
    public Set<ReplicaId> getAllReplicaIds() {
        return replicaMap.keySet();
    }
    
    /**
     * Factory method to create a localhost topology for testing.
     * Creates replicas with sequential indices and port numbers.
     * 
     * @param replicaCount the number of replicas to create
     * @param basePort the starting port number (each replica gets basePort + index*100)
     * @return a new Topology instance
     */
    public static Topology localhost(int replicaCount, int basePort) {
        if (replicaCount <= 0) {
            throw new IllegalArgumentException("Replica count must be positive, got: " + replicaCount);
        }
        
        List<ReplicaConfig> configs = new ArrayList<>();
        for (int i = 0; i < replicaCount; i++) {
            int replicaBasePort = basePort + (i * 100);
            configs.add(ReplicaConfig.localhost(i, replicaBasePort));
        }
        
        return new Topology(configs);
    }
    
    /**
     * Builder class for constructing topologies.
     */
    public static class Builder {
        private final List<ReplicaConfig> replicas = new ArrayList<>();
        
        /**
         * Adds a replica configuration to the topology.
         * 
         * @param config the replica configuration
         * @return this builder for method chaining
         */
        public Builder addReplica(ReplicaConfig config) {
            replicas.add(config);
            return this;
        }
        
        /**
         * Adds a localhost replica with the given index and base port.
         * 
         * @param index the replica index
         * @param basePort the base port number
         * @return this builder for method chaining
         */
        public Builder addLocalhost(int index, int basePort) {
            replicas.add(ReplicaConfig.localhost(index, basePort));
            return this;
        }
        
        /**
         * Builds the topology.
         * 
         * @return a new Topology instance
         */
        public Topology build() {
            return new Topology(replicas);
        }
    }
    
    /**
     * Creates a new builder for constructing topologies.
     * 
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}