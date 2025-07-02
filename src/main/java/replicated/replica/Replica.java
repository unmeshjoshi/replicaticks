package replicated.replica;

import replicated.messaging.NetworkAddress;
import java.util.List;
import java.util.Objects;

public final class Replica {
    
    private final String name;
    private final NetworkAddress networkAddress;
    private final List<NetworkAddress> peers;
    
    public Replica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers) {
        if (name == null) {
            throw new IllegalArgumentException("Name cannot be null");
        }
        if (networkAddress == null) {
            throw new IllegalArgumentException("Network address cannot be null");
        }
        if (peers == null) {
            throw new IllegalArgumentException("Peers list cannot be null");
        }
        
        this.name = name;
        this.networkAddress = networkAddress;
        this.peers = List.copyOf(peers); // Defensive copy to ensure immutability
    }
    
    public String getName() {
        return name;
    }
    
    public NetworkAddress getNetworkAddress() {
        return networkAddress;
    }
    
    public List<NetworkAddress> getPeers() {
        return peers;
    }
    
    /**
     * Called by the simulation loop for each tick.
     * This is where the replica can perform proactive work like
     * sending heartbeats, timing out requests, etc.
     * 
     * @param currentTick the current simulation tick
     */
    public void tick(long currentTick) {
        // For now, this is a no-op. Will be enhanced in Phase 6 for:
        // - Heartbeat management
        // - Request timeout handling  
        // - Quorum state cleanup
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Replica replica = (Replica) obj;
        // Equality based on name and network address only (not peers)
        return Objects.equals(name, replica.name) &&
               Objects.equals(networkAddress, replica.networkAddress);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name, networkAddress);
    }
    
    @Override
    public String toString() {
        return "Replica{" +
                "name='" + name + '\'' +
                ", networkAddress=" + networkAddress +
                ", peers=" + peers.size() + " peers" +
                '}';
    }
} 