package replicated.network.topology;

import replicated.messaging.NetworkAddress;
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

        validateNoDuplicates(replicas);

        // Create immutable copies
        this.replicas = List.copyOf(replicas);
        
        // Build lookup map
        Map<ReplicaId, ReplicaConfig> map = new HashMap<>();
        for (ReplicaConfig config : replicas) {
            map.put(config.replicaId(), config);
        }
        this.replicaMap = Map.copyOf(map);
    }

    private static void validateNoDuplicates(List<ReplicaConfig> replicas) {

        if (replicas.size() != replicas.stream().distinct().count()) {
            throw new IllegalArgumentException("Replicas list contains duplicates"); //replicas.stream().distinct().count() == replicas.size();
        }
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
}