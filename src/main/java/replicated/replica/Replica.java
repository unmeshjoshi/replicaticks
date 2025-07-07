package replicated.replica;

import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.storage.Storage;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for all replica implementations containing common building blocks.
 * This class provides the foundation for different replication algorithms like
 * Quorum-based, Raft, Chain Replication, etc.
 */
public abstract class Replica implements MessageHandler {
    
    // Core replica identity
    protected final String name;
    protected final NetworkAddress networkAddress;
    protected final List<NetworkAddress> peers;
    
    // Infrastructure dependencies
    protected final MessageBus messageBus;
    protected final Storage storage;
    protected final int requestTimeoutTicks;
    
    // Request tracking infrastructure
    protected final AtomicLong requestIdGenerator = new AtomicLong(0);
    protected final Map<String, PendingRequest> pendingRequests = new HashMap<>();
    
    // Internal counter for timeout management (TigerBeetle pattern)
    private long currentTick = 0;

    /**
     * Base constructor for all replica implementations.
     * 
     * @param name unique replica name
     * @param networkAddress network address of this replica
     * @param peers list of peer replica addresses
     * @param messageBus message bus for communication
     * @param storage storage layer for persistence
     * @param requestTimeoutTicks timeout for requests in ticks
     */
    protected Replica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                     MessageBus messageBus, Storage storage, int requestTimeoutTicks) {
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
        this.messageBus = messageBus;
        this.storage = storage;
        this.requestTimeoutTicks = requestTimeoutTicks;
        
        // Validate dependencies
        if (messageBus != null && storage == null) {
            throw new IllegalArgumentException("Storage cannot be null when MessageBus is provided");
        }
        if (storage != null && messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null when Storage is provided");
        }
    }
    
    // Getters for common properties
    public String getName() {
        return name;
    }
    
    public NetworkAddress getNetworkAddress() {
        return networkAddress;
    }
    
    public List<NetworkAddress> getPeers() {
        return peers;
    }
    
    @Override
    public abstract void onMessageReceived(Message message, MessageContext ctx);
    
    /**
     * Common tick() processing for all replica types.
     * This handles infrastructure concerns like storage ticks and timeouts.
     * Subclasses can override to add specific logic.
     * Replica implementations manage their own internal tick counters for timeout management.
     */
    public void tick() {
        if (messageBus == null || storage == null) {
            return;
        }
        
        currentTick++; // Increment internal counter (TigerBeetle pattern)
        
        // Process storage operations first
        storage.tick();
        
        // Handle request timeouts
        handleRequestTimeouts(currentTick);
        
        // Allow subclasses to perform additional tick processing
        onTick(currentTick);
    }
    
    /**
     * Hook method for subclasses to perform additional tick processing.
     * This is called after common timeout handling.
     */
    protected void onTick(long currentTick) {
        // currentTick is already set in the main tick() method
    }
    
    /**
     * Handles request timeouts for all pending requests.
     * Subclasses should implement sendTimeoutResponse() to handle specific timeouts.
     */
    protected void handleRequestTimeouts(long currentTick) {
        List<String> timedOutRequests = new ArrayList<>();
        
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest request = entry.getValue();
            if (currentTick - request.startTick >= requestTimeoutTicks) {
                timedOutRequests.add(entry.getKey());
                
                // Send timeout response to client
                sendTimeoutResponse(request);
            }
        }
        
        // Clean up timed out requests
        for (String requestId : timedOutRequests) {
            pendingRequests.remove(requestId);
        }
    }
    
    /**
     * Sends a timeout response to the client.
     * Subclasses must implement this to handle specific timeout scenarios.
     */
    protected abstract void sendTimeoutResponse(PendingRequest request);
    
    /**
     * Generates a unique request ID for this replica.
     */
    protected String generateRequestId() {
        return name + "-" + requestIdGenerator.incrementAndGet();
    }
    
    /**
     * Gets the current tick for timestamp purposes.
     * In a real implementation, this would come from the simulation.
     */
    protected long getCurrentTick() {
        return System.currentTimeMillis() / 1000; // Simple tick approximation
    }
    
    /**
     * Serializes a payload object to bytes.
     */
    protected byte[] serializePayload(Object payload) {
        try {
            return JsonMessageCodec.createConfiguredObjectMapper().writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }
    
    /**
     * Deserializes bytes to a payload object.
     */
    protected <T> T deserializePayload(byte[] data, Class<T> type) {
        try {
            return JsonMessageCodec.createConfiguredObjectMapper().readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize payload", e);
        }
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
        return getClass().getSimpleName() + "{" +
                "name='" + name + '\'' +
                ", networkAddress=" + networkAddress +
                ", peers=" + peers +
                '}';
    }
    
    /**
     * Abstract base class for tracking pending requests.
     * Subclasses can extend this to add algorithm-specific state.
     */
    protected static abstract class PendingRequest {
        public final String requestId;
        public final NetworkAddress clientAddress;
        public final String key;
        public final long startTick;
        
        protected PendingRequest(String requestId, NetworkAddress clientAddress, String key, long startTick) {
            this.requestId = requestId;
            this.clientAddress = clientAddress;
            this.key = key;
            this.startTick = startTick;
        }
    }
} 