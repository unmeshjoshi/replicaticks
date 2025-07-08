package replicated.replica;

import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.storage.Storage;
import replicated.util.Timeout;

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
    protected final BaseMessageBus messageBus;
    protected final Storage storage;
    protected final int requestTimeoutTicks;
    protected final RequestWaitingList waitingList;
    // Request tracking infrastructure
    protected final AtomicLong requestIdGenerator = new AtomicLong(0);
   

    /**
     * Base constructor for all replica implementations.
     *
     * @param name                unique replica name
     * @param networkAddress      network address of this replica
     * @param peers               list of peer replica addresses
     * @param messageBus          message bus for communication
     * @param storage             storage layer for persistence
     * @param requestTimeoutTicks timeout for requests in ticks
     */
    protected Replica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                      BaseMessageBus messageBus, Storage storage, int requestTimeoutTicks) {
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
        this.waitingList = new RequestWaitingList(requestTimeoutTicks);

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
     */
    public void tick() {
        if (messageBus == null || storage == null) {
            return;
        }

        // Tick the main timeout object
        waitingList.tick();


        // Allow subclasses to perform additional tick processing
        onTick();
    }

    /**
     * Hook method for subclasses to perform additional tick processing.
     * This is called after common timeout handling.
     */
    protected void onTick() {
        // Subclasses can override to add specific tick processing
    }

    /**
     * Generates a unique request ID for this replica.
     */
    protected String generateRequestId() {
        return name + "-" + requestIdGenerator.incrementAndGet();
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

}
