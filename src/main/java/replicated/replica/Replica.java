package replicated.replica;

import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.network.id.ReplicaId;
import replicated.storage.Storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Base class for all server-side replicas in a distributed key-value store with quorum-based consensus.
 * This class provides the foundational infrastructure for implementing distributed replicas that participate
 * in a deterministic simulation environment, handling both client requests and internal replica coordination.
 * <p>
 * In the distributed system architecture, each replica maintains a copy of the data and participates in
 * consensus decisions through message passing with peer replicas. This base class handles the common
 * infrastructure concerns while allowing subclasses to implement specific consensus algorithms.
 * <p>
 * Responsibilities
 * <ul>
 *   <li>Maintain immutable identity – {@code name} and {@code networkAddress} – and the list of peer replicas.</li>
 *   <li>Require and hold non-null {@link MessageBus} and {@link Storage} dependencies with constructor validation.</li>
 *   <li>Manage the event-driven lifecycle through {@link #tick()} method for timeout processing and periodic tasks.</li>
 *   <li>Handle message routing and correlation ID generation for internal replica-to-replica communication.</li>
 *   <li>Provide helper utilities:
 *     <ul>
 *       <li>{@link #generateRequestId()} – unique IDs for client-visible operations.</li>
 *       <li>{@link #broadcastToAllReplicas(AsyncQuorumCallback, BiFunction)} – fan-out internal RPCs with quorum handling.</li>
 *       <li>{@link #serializePayload(Object)} / {@link #deserializePayload(byte[], Class)} – JSON serialization helpers.</li>
 *       <li>{@link #getAllNodes()} – convenience accessor (self + peers).</li>
 *     </ul>
 *   </li>
 *   <li>Manage {@link RequestWaitingList} for outstanding internal requests and advance their timeouts in {@link #tick()}.</li>
 * </ul>
 * <p>
 * Lifecycle and Initialization
 * <ul>
 *   <li>Constructor validates all required dependencies and throws {@link IllegalArgumentException} for null values.</li>
 *   <li>Replica identity (name, network address, peers) is immutable after construction.</li>
 *   <li>The replica operates in an event-driven manner, processing messages and timeouts through {@link #tick()}.</li>
 *   <li>Subclasses must implement {@link #onMessageReceived(Message, MessageContext)} to handle incoming messages.</li>
 * </ul>
 * <p>
 * Extension Points for Subclasses
 * <ol>
 *   <li>{@link #onMessageReceived(Message, MessageContext)} – <strong>Required:</strong> Handle all inbound messages 
 *       destined for this replica. This is where consensus algorithm logic is implemented.</li>
 *   <li>{@link #onTick()} – <strong>Optional:</strong> Perform replica-specific periodic work. Called after 
 *       common timeout processing. Must call {@code super.onTick()} if overridden.</li>
 * </ol>
 * <p>
 * Usage Guidelines
 * <ul>
 *   <li>Subclasses <b>must</b> call {@code super.tick()} if they override {@code tick()}.</li>
 *   <li>Use {@code broadcastToAllReplicas} with {@link AsyncQuorumCallback} to simplify quorum-based RPC flows.</li>
 *   <li>Always handle message processing errors appropriately in {@link #onMessageReceived}.</li>
 *   <li><strong>Tick Orchestration:</strong> Only tick internal implementation details (like {@link RequestWaitingList}). 
 *       Never call {@code tick()} on dependencies like {@link MessageBus} or {@link Storage} -
 *       that is handled by {@link SimulationDriver} to maintain centralized tick orchestration.</li>
 * </ul>
 * <p>
 * Thread Safety and Concurrency
 * <ul>
 *   <li>The class is <em>not</em> thread-safe and expects single-threaded event-loop execution.</li>
 *   <li>All methods should be called from the same thread to ensure consistent state.</li>
 *   <li>The deterministic simulation environment assumes single-threaded execution for reproducible results.</li>
 * </ul>
 * <p>
 * Error Handling
 * <ul>
 *   <li>Constructor validation throws {@link IllegalArgumentException} for invalid parameters.</li>
 *   <li>Serialization/deserialization methods throw {@link RuntimeException} for JSON processing errors.</li>
 *   <li>Timeout handling is automatic through the {@link RequestWaitingList} mechanism.</li>
 *   <li>Subclasses should handle message processing errors appropriately in {@link #onMessageReceived}.</li>
 * </ul>
 * <p>
 * Usage Example:
 * <pre>{@code
 * public class MyReplica extends Replica {
 *     public MyReplica(String name, NetworkAddress address, List<NetworkAddress> peers,
 *                      BaseMessageBus messageBus, Storage storage, int timeoutTicks) {
 *         super(name, address, peers, messageBus, storage, timeoutTicks);
 *     }
 *     
 *     @Override
 *     public void onMessageReceived(Message message, MessageContext ctx) {
 *         // Handle incoming messages
 *         if (message.getType() == MessageType.GET_REQUEST) {
 *             // Process GET request
 *         }
 *     }
 *     
 *     @Override
 *     protected void onTick() {
 *         // Perform periodic tasks
 *         super.onTick(); // Always call super if overriding
 *     }
 * }
 * }</pre>
 *
 * @see QuorumReplica
 * @see MessageBus
 * @see AsyncQuorumCallback
 */
public abstract class Replica implements MessageHandler {

    // Core replica identity
    protected final ReplicaId replicaId;
    protected final NetworkAddress networkAddress;
    protected final List<NetworkAddress> peers;
    protected final List<ReplicaId> peerIds;

    // Infrastructure dependencies
    protected final MessageBus messageBus;
    protected final MessageCodec messageCodec;
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
     * @param messageCodec        codec for message and payload serialization
     * @param storage             storage layer for persistence
     * @param requestTimeoutTicks timeout for requests in ticks
     */
    protected Replica(ReplicaId replicaId, NetworkAddress networkAddress, List<NetworkAddress> peers,
                      MessageBus messageBus, MessageCodec messageCodec, Storage storage, int requestTimeoutTicks, List<ReplicaId> peerIds) {
        checkArguments(replicaId, networkAddress, peers, messageBus, messageCodec, storage);

        this.replicaId = replicaId;
        this.networkAddress = networkAddress;
        this.peerIds = List.copyOf(peerIds);
        this.peers = List.copyOf(peers); // Defensive copy to ensure immutability
        this.messageBus = messageBus;
        messageBus.registerHandler(replicaId, this);
        messageBus.registerHandler(networkAddress, this);

        this.messageCodec = messageCodec;
        this.storage = storage;
        this.requestTimeoutTicks = requestTimeoutTicks;
        this.waitingList = new RequestWaitingList(requestTimeoutTicks);



    }

    private static void checkArguments(ReplicaId replicaId, NetworkAddress networkAddress, List<NetworkAddress> peers,
                                       MessageBus messageBus, MessageCodec messageCodec, Storage storage) {
        if (replicaId == null) {
            throw new IllegalArgumentException("Name cannot be null");
        }
        if (networkAddress == null) {
            throw new IllegalArgumentException("Network address cannot be null");
        }
        if (peers == null) {
            throw new IllegalArgumentException("Peers list cannot be null");
        }
        // Validate dependencies: messageBus, messageCodec, and storage must be provided (non-null)
        if (messageBus == null || messageCodec == null || storage == null) {
            throw new IllegalArgumentException("MessageBus, MessageCodec, and Storage must be provided and non-null");
        }
    }

    // Getters for common properties
    public String getName() {
        return replicaId.name();
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
        return replicaId.name() + "-" + requestIdGenerator.incrementAndGet();
    }


    /**
     * Serializes a payload object to bytes.
     */
    protected byte[] serializePayload(Object payload) {
        return messageCodec.encode(payload);
    }

    /**
     * Deserializes bytes to a payload object.
     */
    protected <T> T deserializePayload(byte[] data, Class<T> type) {
        return messageCodec.decode(data, type);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "name='" + replicaId + '\'' +
                ", networkAddress=" + networkAddress +
                ", peers=" + peers +
                '}';
    }

    /**
     * Generates a unique correlation ID for internal messages.
     */
    private String generateCorrelationId() {
        return "internal-" + UUID.randomUUID();
        //internal correlation ID should be UUID as it should not use System.currentTimeMillis
        //Multiple internal messages can be sent at the same millisecond.
    }

    /**
     * Gets all nodes in the cluster (peers + self).
     */
    protected List<NetworkAddress> getAllNodes() {
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);
        return allNodes;
    }

    /**
     * Generic helper to broadcast an internal request to all nodes (peers + self).
     * It handles correlation ID generation, waiting list registration and message sending.
     */
    protected <T> void broadcastToAllReplicas(AsyncQuorumCallback<T> quorumCallback,
                                              BiFunction<NetworkAddress, String, Message> messageBuilder) {
        for (NetworkAddress node : getAllNodes()) {
            String internalCorrelationId = generateCorrelationId();
            waitingList.add(internalCorrelationId, quorumCallback);

            Message internalMessage = messageBuilder.apply(node, internalCorrelationId);
            messageBus.sendMessage(internalMessage);
        }
    }

    public ReplicaId getReplicaId() {
        return replicaId;
    }
}
