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
    NetworkAddress internodeAddress,
    NetworkAddress clientRpcAddress
) {
    
    /**
     * Compact constructor with validation.
     * 
     * @param replicaId the replica identifier (must not be null)
     * @param internodeAddress the network address for internode communication (must not be null)
     * @param clientRpcAddress the network address for client RPC communication (must not be null)
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if addresses are the same
     */
    public ReplicaConfig {
        Objects.requireNonNull(replicaId, "ReplicaId cannot be null");
        Objects.requireNonNull(internodeAddress, "Internode address cannot be null");
        Objects.requireNonNull(clientRpcAddress, "Client RPC address cannot be null");
        
        if (internodeAddress.equals(clientRpcAddress)) {
            throw new IllegalArgumentException("Internode address and client RPC address cannot be the same: " + internodeAddress);
        }
    }
    
    /**
     * Factory method to create a ReplicaConfig with localhost and default port offset.
     * 
     * @param index the replica index (must be non-negative)
     * @param basePort the base port number (internode port = basePort, client RPC port = basePort + 1000)
     * @return a new ReplicaConfig instance
     */
    public static ReplicaConfig localhost(int index, int basePort) {
        ReplicaId replicaId = ReplicaId.of(index);
        NetworkAddress internodeAddr = new NetworkAddress("localhost", basePort);
        NetworkAddress clientRpcAddr = new NetworkAddress("localhost", basePort + 1000);
        return new ReplicaConfig(replicaId, internodeAddr, clientRpcAddr);
    }
    
    /**
     * Factory method to create a ReplicaConfig with custom host and default port offset.
     * 
     * @param index the replica index (must be non-negative)
     * @param host the hostname or IP address
     * @param basePort the base port number (internode port = basePort, client RPC port = basePort + 1000)
     * @return a new ReplicaConfig instance
     */
    public static ReplicaConfig of(int index, String host, int basePort) {
        ReplicaId replicaId = ReplicaId.of(index);
        NetworkAddress internodeAddr = new NetworkAddress(host, basePort);
        NetworkAddress clientRpcAddr = new NetworkAddress(host, basePort + 1000);
        return new ReplicaConfig(replicaId, internodeAddr, clientRpcAddr);
    }
    
    /**
     * Returns the replica index for convenience.
     * 
     * @return the replica index
     */
    public int index() {
        return replicaId.index();
    }
    
    /**
     * Returns a string representation of the internode address.
     * 
     * @return host:internodePort format
     */
    public String internodeAddressString() {
        return internodeAddress.ipAddress() + ":" + internodeAddress.port();
    }
    
    /**
     * Returns a string representation of the client RPC address.
     * 
     * @return host:clientRpcPort format
     */
    public String clientRpcAddressString() {
        return clientRpcAddress.ipAddress() + ":" + clientRpcAddress.port();
    }
}