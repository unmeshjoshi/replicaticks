package replicated.network.id;

import java.util.Objects;

/**
 * Immutable record representing a replica identifier in the distributed system.
 * Each replica is identified by a unique integer index and has a human-readable name.
 */
public record ReplicaId(int index, String name) implements ProcessId {
    
    /**
     * Compact constructor with validation.
     * 
     * @param index the replica index (must be non-negative)
     * @param name the replica name (must not be null)
     * @throws IllegalArgumentException if index is negative
     * @throws NullPointerException if name is null
     */
    public ReplicaId {
        if (index < 0) {
            throw new IllegalArgumentException("Replica index must be non-negative, got: " + index);
        }
        Objects.requireNonNull(name, "Replica name cannot be null");
    }
    
    /**
     * Convenience constructor that generates a default name based on the index.
     * 
     * @param index the replica index (must be non-negative)
     */
    public ReplicaId(int index) {
        this(index, "replica-" + index);
    }
    
    /**
     * Factory method to create a ReplicaId from an integer index.
     * 
     * @param index the replica index as an integer (must be non-negative)
     * @return a new ReplicaId instance
     * @throws IllegalArgumentException if index is negative
     */
    public static ReplicaId of(int index) {
        return new ReplicaId(index);
    }
    
    /**
     * Factory method to create a ReplicaId from an integer index with a custom name.
     * 
     * @param index the replica index as an integer (must be non-negative)
     * @param name the replica name (must not be null)
     * @return a new ReplicaId instance
     * @throws IllegalArgumentException if index is negative
     * @throws NullPointerException if name is null
     */
    public static ReplicaId of(int index, String name) {
        return new ReplicaId(index, name);
    }
}