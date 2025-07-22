package replicated.network.topology;

import replicated.network.id.ReplicaId;
import replicated.messaging.NetworkAddress;
import java.util.Objects;

/**
 * Immutable configuration for a single replica in the cluster topology.
 * Contains network addressing and other operational details for a replica.
 */
public record ReplicaConfig(
    ReplicaId replicaId,
    NetworkAddress address
) {
    
    /**
     * Compact constructor with validation.
     * 
     * @param replicaId the replica identifier (must not be null)
     * @param address the network address for internode communication (must not be null)
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if addresses are the same
     */
    public ReplicaConfig {
        Objects.requireNonNull(replicaId, "ReplicaId cannot be null");
        Objects.requireNonNull(address, "Internode address cannot be null");
    }

    
    /**
     * Factory method to create a ReplicaConfig with custom host and default port offset.
     * 
     * @param index the replica index (must be non-negative)
     * @param host the hostname or IP address
     * @param port the base port number (internode port = port, client RPC port = port + 1000)
     * @return a new ReplicaConfig instance
     */
    public static ReplicaConfig of(int index, String host, int port) {
        ReplicaId replicaId = ReplicaId.of(index);
        NetworkAddress internodeAddr = new NetworkAddress(host, port);
        return new ReplicaConfig(replicaId, internodeAddr);
    }
    
    /**
     * Returns the replica index for convenience.
     * 
     * @return the replica index
     */
    public int index() {
        return replicaId.index();
    }

}