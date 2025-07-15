package replicated.client;

import replicated.future.ListenableFuture;
import replicated.messaging.*;
import replicated.network.MessageContext;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic cluster client that provides cluster communication capabilities.
 * This class uses composition and can be embedded in specific client implementations
 * for different cluster types (quorum, raft, gossip, etc.).
 */
public final class ClusterClient implements MessageHandler {
    
    private final MessageBus messageBus;
    private final MessageCodec messageCodec;
    private final int requestTimeoutTicks;
    private final String clientId;
    
    // Bootstrap and cluster discovery
    private final List<NetworkAddress> bootstrapReplicas;
    private final Set<NetworkAddress> knownReplicas = new HashSet<>();
    private final Random random = new Random();
    
    // Connection management
    private final int maxRetries = 3;
    
    // Request tracking - using RequestWaitingList for timeout handling
    private final AtomicLong correlationIdGenerator = new AtomicLong(0);
    private final RequestWaitingList<String, Object> requestWaitingList;
    
    // Connection pool rotation index
    private int replicaIndex = 0;
    
    // Delegate for handling protocol-specific messages
    private MessageHandler protocolHandler;
    private NetworkAddress currentClientAddress;

    /**
     * Creates a ClusterClient with bootstrap replicas for cluster discovery.
     *
     * @param messageBus the message bus for communication
     * @param messageCodec the codec for message and payload serialization
     * @param bootstrapReplicas initial list of replica addresses for cluster discovery
     * @param clientIdPrefix prefix for generating unique client IDs
     */
    public ClusterClient(MessageBus messageBus, MessageCodec messageCodec, List<NetworkAddress> bootstrapReplicas, String clientIdPrefix) {
        this(messageBus, messageCodec, bootstrapReplicas, clientIdPrefix, 200);
    }
    
    /**
     * Full constructor with configurable timeout.
     */
    public ClusterClient(MessageBus messageBus, MessageCodec messageCodec, List<NetworkAddress> bootstrapReplicas, String clientIdPrefix, int requestTimeoutTicks) {
        if (messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null");
        }
        if (messageCodec == null) {
            throw new IllegalArgumentException("MessageCodec cannot be null");
        }
        if (bootstrapReplicas == null || bootstrapReplicas.isEmpty()) {
            throw new IllegalArgumentException("Bootstrap replicas cannot be null or empty");
        }
        if (clientIdPrefix == null || clientIdPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Client ID prefix cannot be null or empty");
        }
        
        this.messageBus = messageBus;
        this.messageCodec = messageCodec;
        this.bootstrapReplicas = new ArrayList<>(bootstrapReplicas);
        this.requestTimeoutTicks = requestTimeoutTicks;
        this.clientId = clientIdPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Initialize request tracking using RequestWaitingList
        this.requestWaitingList = new RequestWaitingList<>(requestTimeoutTicks);
        
        // Initialize known replicas with bootstrap replicas
        this.knownReplicas.addAll(bootstrapReplicas);
    }
    
    /**
     * Set the protocol-specific message handler that will receive messages.
     * This allows the embedding client to handle protocol-specific logic.
     */
    public void setMessageHandler(MessageHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
    }
    
    public String getClientId() {
        return clientId;
    }
    
    /**
     * Gets the message bus used by this client.
     * @return the message bus instance
     */
    public MessageBus getMessageBus() {
        return messageBus;
    }
    
    /**
     * Send a generic request with automatic replica selection and failover.
     * @param request the request payload object
     * @param messageType the message type for this request
     * @param responseHandler callback to handle the response
     * @return a future that will be completed with the result
     */
    public <T> ListenableFuture<T> sendRequestWithFailover(Object request, MessageType messageType, ResponseHandler<T> responseHandler) {
        return sendRequestWithFailover(request, messageType, responseHandler, 0);
    }
    
    /**
     * Send a generic request to a specific replica.
     * @param request the request payload object
     * @param messageType the message type for this request
     * @param replicaAddress the specific replica to send to
     * @param responseHandler callback to handle the response
     * @return a future that will be completed with the result
     */
    public <T> ListenableFuture<T> sendRequest(Object request, MessageType messageType, NetworkAddress replicaAddress, ResponseHandler<T> responseHandler) {
        String correlationId = generateCorrelationId();
        ListenableFuture<T> future = new ListenableFuture<>();
        
        // Use RequestWaitingList to track the request
        requestWaitingList.add(correlationId, new RequestCallback<Object>() {
            @Override
            public void onResponse(Object response, NetworkAddress fromNode) {
                try {
                    T result = responseHandler.handleResponse(response);
                    future.complete(result);
                } catch (Exception e) {
                    future.fail(e);
                }
            }
            
            @Override
            public void onError(Exception error) {
                future.fail(error);
            }
        });
        
        // Send client message for this request
        sendClientMessage(replicaAddress, messageType, request, correlationId);

        return future;
    }
    
    // Internal methods with failover logic
    
    private <T> ListenableFuture<T> sendRequestWithFailover(Object request, MessageType messageType, ResponseHandler<T> responseHandler, int attempt) {
        if (attempt >= maxRetries) {
            ListenableFuture<T> failedFuture = new ListenableFuture<>();
            failedFuture.fail(new RuntimeException("Max retries exceeded for " + messageType + " request"));
            return failedFuture;
        }
        
        NetworkAddress replica = selectNextReplica();
        ListenableFuture<T> future = sendRequest(request, messageType, replica, responseHandler);
        
        // Add failure handling for automatic retry
        future.onFailure(error -> {
            // Try next replica on failure
            sendRequestWithFailover(request, messageType, responseHandler, attempt + 1);
        });
        
        return future;
    }

    public void sendClientMessage(NetworkAddress destination, MessageType messageType, Object request, String correlationId) {
        // Register correlation handler that will route responses back to us
        messageBus.registerHandler(correlationId, this);
        
        byte[] payload = serializePayload(request);

        // Use dynamic client address registration
        if (currentClientAddress == null) {
            // Generate a unique client address for this request
            //TODO: We need to have a ProcessID abstraction to identify clients and replicas.
            //We can then use ProcessID to map messages. The network layer can keep the map of processIDs to
            //network addresses of replicas. For clients we do not need to explicitly map ip addresses
            //as the network messages will be sent over the connected channel.
            NetworkAddress clientAddress = new NetworkAddress("client-" + clientId, 8080);
            currentClientAddress = clientAddress;
        }

        Message message = new Message(currentClientAddress, destination, messageType, payload, correlationId);
        messageBus.sendMessage(message);
    }

    public NetworkAddress selectNextReplica() {
        if (knownReplicas.isEmpty()) {
            // Fallback to bootstrap replicas if no known replicas
            return bootstrapReplicas.get(replicaIndex % bootstrapReplicas.size());
        }
        
        List<NetworkAddress> replicaList = new ArrayList<>(knownReplicas);
        NetworkAddress selectedReplica = replicaList.get(replicaIndex % replicaList.size());
        replicaIndex = (replicaIndex + 1) % replicaList.size();
        return selectedReplica;
    }

    /**
     * Delegate message handling to the protocol-specific handler if set,
     * otherwise handle generic cluster management messages.
     */
    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        if (protocolHandler != null) {
            protocolHandler.onMessageReceived(message, ctx);
        } else {
            System.out.println("ClusterClient: No protocol handler set, ignoring message: " + message.messageType());
        }
    }

    /**
     * Process timeouts and other periodic client operations.
     * This method should be called periodically in the simulation tick loop.
     */
    public void tick() {
        // Delegate timeout handling to RequestWaitingList
        requestWaitingList.tick();
    }

    public String generateCorrelationId() {
        return clientId + "-" + correlationIdGenerator.incrementAndGet();
    }

    public byte[] serializePayload(Object payload) {
        return messageCodec.encode(payload);
    }

    public <T> T deserializePayload(byte[] data, Class<T> type) {
        return messageCodec.decode(data, type);
    }
    
    /**
     * Get access to the request waiting list for advanced request management.
     */
    public RequestWaitingList<String, Object> getRequestWaitingList() {
        return requestWaitingList;
    }
    
    /**
     * Get the known replicas set (read-only view).
     */
    public Set<NetworkAddress> getKnownReplicas() {
        return Collections.unmodifiableSet(knownReplicas);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ClusterClient)) return false;
        ClusterClient client = (ClusterClient) obj;
        return Objects.equals(clientId, client.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId);
    }

    @Override
    public String toString() {
        return "ClusterClient{id=" + clientId + ", replicas=" + knownReplicas.size() + "}";
    }
    
    /**
     * Interface for handling responses in a type-safe manner.
     */
    @FunctionalInterface
    public interface ResponseHandler<T> {
        T handleResponse(Object response) throws Exception;
    }
} 