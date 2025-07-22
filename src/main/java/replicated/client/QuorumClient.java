package replicated.client;

import replicated.future.ListenableFuture;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.network.id.ClientId;
import replicated.network.id.ReplicaId;
import replicated.storage.VersionedValue;

import java.util.List;

/**
 * Client for quorum-based replicated key-value store.
 * Provides GET and SET operations with automatic failover and replica selection.
 * Uses composition with ClusterClient for generic cluster communication.
 */
public class QuorumClient implements MessageHandler {
    
    // Composition: Use ClusterClient for generic cluster communication
    final ClusterClient clusterClient; // package-private for Client access
    
    /**
     * Creates a QuorumClient with bootstrap replicas for cluster discovery.
     * Follows the Kafka bootstrap pattern - client starts with one or more replica addresses
     * and discovers the full cluster topology dynamically.
     * 
     * @param messageBus the message bus for communication
     * @param messageCodec the codec for message and payload serialization
     * @param bootstrapReplicas initial list of replica addresses for cluster discovery
     */
    public QuorumClient(MessageBus messageBus, MessageCodec messageCodec, List<NetworkAddress> bootstrapReplicas, List<ReplicaId> bootstrapReplicaIds) {
        this(messageBus, messageCodec, bootstrapReplicas, 200, bootstrapReplicaIds);
    }
    
    /**
     * Full constructor with configurable timeout.
     */
    public QuorumClient(MessageBus messageBus, MessageCodec messageCodec, List<NetworkAddress> bootstrapReplicas, int requestTimeoutTicks, List<ReplicaId> bootstrapReplicaIds) {
        // Create ClusterClient with quorum-specific prefix
        this.clusterClient = new ClusterClient(messageBus, messageCodec, bootstrapReplicas, "quorum-client", requestTimeoutTicks, bootstrapReplicaIds);

        // Set this as the protocol handler for the ClusterClient
        this.clusterClient.setMessageHandler(this);
    }
    
    /**
     * Constructor that accepts a pre-configured ClusterClient.
     * This allows for custom ClusterClient configurations (e.g., different client ID prefixes).
     */
    public QuorumClient(ClusterClient clusterClient) {
        this.clusterClient = clusterClient;
        
        // Set this as the protocol handler for the ClusterClient
        this.clusterClient.setMessageHandler(this);
    }
    
    /**
     * Get the client ID from the underlying cluster client.
     */
    public ClientId getClientId() {
        return clusterClient.getClientId();
    }
    
    /**
     * Gets the message bus used by this client.
     * @return the message bus instance
     */
    public MessageBus getMessageBus() {
        return clusterClient.getMessageBus();
    }
    
    /**
     * Send a GET request using automatic replica selection and failover.
     */
    public ListenableFuture<VersionedValue> sendGetRequest(String key) {
        GetRequest request = new GetRequest(key);
        return clusterClient.sendRequestWithFailover(request, MessageType.CLIENT_GET_REQUEST, this::handleGetResponse);
    }
    
    /**
     * Send a GET request to a specific replica (for cases where replica selection is needed).
     */
    public ListenableFuture<VersionedValue> sendGetRequest(String key, NetworkAddress replicaAddress) {
        GetRequest request = new GetRequest(key);
        return clusterClient.sendRequest(request, MessageType.CLIENT_GET_REQUEST, replicaAddress, this::handleGetResponse);
    }
    
    /**
     * Send a SET request using automatic replica selection and failover.
     */
    public ListenableFuture<Boolean> sendSetRequest(String key, byte[] value) {
        SetRequest request = new SetRequest(key, value);
        return clusterClient.sendRequestWithFailover(request, MessageType.CLIENT_SET_REQUEST, this::handleSetResponse);
    }
    
    /**
     * Send a SET request to a specific replica (for cases where replica selection is needed).
     */
    public ListenableFuture<Boolean> sendSetRequest(String key, byte[] value, NetworkAddress replicaAddress) {
        SetRequest request = new SetRequest(key, value);
        return clusterClient.sendRequest(request, MessageType.CLIENT_SET_REQUEST, replicaAddress, this::handleSetResponse);
    }
    
    /**
     * Process timeouts and other periodic client operations.
     * This method should be called periodically in the simulation tick loop.
     */
    public void tick() {
        clusterClient.tick();
    }
    
    /**
     * Handle GET responses by extracting the VersionedValue.
     */
    private VersionedValue handleGetResponse(Object response) throws Exception {
        if (response instanceof GetResponse) {
            GetResponse getResponse = (GetResponse) response;
            return getResponse.value(); // Can be null for "not found"
        } else {
            throw new RuntimeException("Expected GetResponse but got: " + response.getClass());
        }
    }
    
    /**
     * Handle SET responses by extracting the success status.
     */
    private Boolean handleSetResponse(Object response) throws Exception {
        if (response instanceof SetResponse) {
            SetResponse setResponse = (SetResponse) response;
            return setResponse.success();
        } else {
            throw new RuntimeException("Expected SetResponse but got: " + response.getClass());
        }
    }
    
    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        System.out.println("QuorumClient: Received message " + message.messageType() + " from " + message.source() + 
                          " (correlationId=" + message.correlationId() + ")");
        
        // Verify this is a client response message
        if (message.messageType() != MessageType.CLIENT_GET_RESPONSE && 
            message.messageType() != MessageType.CLIENT_SET_RESPONSE) {
            System.out.println("QuorumClient: Ignoring non-client-response message: " + message.messageType());
            return;
        }
        
        String correlationId = message.correlationId();
        if (correlationId == null) {
            System.out.println("QuorumClient: Received message without correlation ID");
            return;
        }
        
        try {
            if (message.messageType() == MessageType.CLIENT_GET_RESPONSE) {
                // Deserialize as GetResponse
                GetResponse getResponse = clusterClient.deserializePayload(message.payload(), GetResponse.class);
                System.out.println("QuorumClient: Received GET response - key: " + getResponse.key() + 
                                 ", value: " + (getResponse.value() != null ? "found" : "not found") + ", correlationId: " + correlationId);
                
                // Route to RequestWaitingList
                clusterClient.getRequestWaitingList().handleResponse(correlationId, getResponse, message.source());
                
            } else if (message.messageType() == MessageType.CLIENT_SET_RESPONSE) {
                // Deserialize as SetResponse
                SetResponse setResponse = clusterClient.deserializePayload(message.payload(), SetResponse.class);
                System.out.println("QuorumClient: Received SET response - key: " + setResponse.key() + 
                                 ", success: " + setResponse.success() + ", correlationId: " + correlationId);
                
                // Route to RequestWaitingList
                clusterClient.getRequestWaitingList().handleResponse(correlationId, setResponse, message.source());
            }
            
        } catch (Exception ex) {
            System.out.println("QuorumClient: Failed to deserialize response payload: " + ex.getMessage());
            // Handle as error
            clusterClient.getRequestWaitingList().handleError(correlationId, 
                new RuntimeException("Failed to deserialize response", ex));
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof QuorumClient)) return false;
        QuorumClient client = (QuorumClient) obj;
        return clusterClient.equals(client.clusterClient);
    }

    @Override
    public int hashCode() {
        return clusterClient.hashCode();
    }

    @Override
    public String toString() {
        return "QuorumClient{clusterClient=" + clusterClient + "}";
    }
} 