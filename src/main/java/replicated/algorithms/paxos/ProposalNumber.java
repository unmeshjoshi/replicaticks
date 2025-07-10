package replicated.algorithms.paxos;

import replicated.messaging.NetworkAddress;

/**
 * Represents a proposal number in Paxos algorithm with generation number and replica ID.
 */
public final class ProposalNumber implements Comparable<ProposalNumber> {
    private long generation;
    private NetworkAddress replicaId;
    
    // Default constructor for JSON deserialization
    public ProposalNumber() {
    }
    
    public ProposalNumber(long generation, NetworkAddress replicaId) {
        if (generation < 0) {
            throw new IllegalArgumentException("Generation must be non-negative");
        }
        if (replicaId == null) {
            throw new IllegalArgumentException("Replica ID cannot be null");
        }
        this.generation = generation;
        this.replicaId = replicaId;
    }
    
    public long getGeneration() {
        return generation;
    }
    
    public NetworkAddress getReplicaId() {
        return replicaId;
    }
    
    @Override
    public int compareTo(ProposalNumber other) {
        // First compare by generation number
        int generationCompare = Long.compare(this.generation, other.generation);
        if (generationCompare != 0) {
            return generationCompare;
        }
        
        // If generations are equal, compare by replica ID (lexicographically)
        return this.replicaId.toString().compareTo(other.replicaId.toString());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProposalNumber)) return false;
        ProposalNumber other = (ProposalNumber) obj;
        return generation == other.generation && replicaId.equals(other.replicaId);
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(generation) * 31 + replicaId.hashCode();
    }
    
    @Override
    public String toString() {
        return generation + "." + replicaId.toString();
    }
    
    /**
     * Creates a new ProposalNumber with incremented generation.
     */
    public ProposalNumber increment() {
        return new ProposalNumber(generation + 1, replicaId);
    }
    
    /**
     * Returns true if this proposal number is higher than the other.
     */
    public boolean isHigherThan(ProposalNumber other) {
        return this.compareTo(other) > 0;
    }
} 