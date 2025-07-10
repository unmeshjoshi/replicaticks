package replicated.client;

import replicated.future.ListenableFuture;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.storage.VersionedValue;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Client implements MessageHandler {
    
    private final MessageBus messageBus;
    private final MessageCodec messageCodec;
    private final int requestTimeoutTicks;
    private final String clientId;
    
    // Bootstrap and cluster discovery
    private final List<NetworkAddress> bootstrapReplicas;
    private final Set<NetworkAddress> knownReplicas = new HashSet<>();
    private final Random random = new Random();
    
    // Connection management
    private NetworkAddress currentClientAddress;
    private int connectionAttempts = 0;
    private final int maxRetries = 3;
    
    // Request tracking - now using RequestWaitingList instead of manual tracking
    private final AtomicLong correlationIdGenerator = new AtomicLong(0);
    private final RequestWaitingList<String, Object> requestWaitingList;
    
    // Connection pool rotation index
    private int replicaIndex = 0;
    
    /**
     * Creates a Client with bootstrap replicas for cluster discovery.
     * Follows the Kafka bootstrap pattern - client starts with one or more replica addresses
     * and discovers the full cluster topology dynamically.
     * 
     * @param messageBus the message bus for communication
     * @param messageCodec the codec for message and payload serialization
     * @param bootstrapReplicas initial list of replica addresses for cluster discovery
     */
    public Client(MessageBus messageBus, MessageCodec messageCodec, List<NetworkAddress> bootstrapReplicas) {
        this(messageBus, messageCodec, bootstrapReplicas, 200);
    }
    
    /**
     * Full constructor with configurable timeout.
     */
    public Client(MessageBus messageBus, MessageCodec messageCodec, List<NetworkAddress> bootstrapReplicas, int requestTimeoutTicks) {
        if (messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null");
        }
        if (messageCodec == null) {
            throw new IllegalArgumentException("MessageCodec cannot be null");
        }
        if (bootstrapReplicas == null || bootstrapReplicas.isEmpty()) {
            throw new IllegalArgumentException("Bootstrap replicas cannot be null or empty");
        }
        
        this.messageBus = messageBus;
        this.messageCodec = messageCodec;
        this.bootstrapReplicas = new ArrayList<>(bootstrapReplicas);
        this.requestTimeoutTicks = requestTimeoutTicks;
        this.clientId = "client-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Initialize request tracking using RequestWaitingList
        this.requestWaitingList = new RequestWaitingList<>(requestTimeoutTicks);
        
        // Initialize known replicas with bootstrap replicas
        this.knownReplicas.addAll(bootstrapReplicas);
        
        // Don't register upfront - register dynamically when sending messages
    }
    
    // Backward compatibility constructor (for tests)
    public Client(MessageBus messageBus) {
        // Create a single bootstrap replica for backward compatibility
        this(messageBus, new JsonMessageCodec(), List.of(new NetworkAddress("127.0.0.1", 8080)));
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
     * Send a GET request using automatic replica selection and failover.
     */
    public ListenableFuture<VersionedValue> sendGetRequest(String key) {
        return sendGetRequestWithFailover(key, 0);
    }
    
    /**
     * Send a GET request to a specific replica (for cases where replica selection is needed).
     */
    public ListenableFuture<VersionedValue> sendGetRequest(String key, NetworkAddress replicaAddress) {
        return sendGetRequestInternal(key, replicaAddress);
    }
    
    /**
     * Send a SET request using automatic replica selection and failover.
     */
    public ListenableFuture<Boolean> sendSetRequest(String key, byte[] value) {
        return sendSetRequestWithFailover(key, value, 0);
    }
    
    /**
     * Send a SET request to a specific replica (for cases where replica selection is needed).
     */
    public ListenableFuture<Boolean> sendSetRequest(String key, byte[] value, NetworkAddress replicaAddress) {
        return sendSetRequestInternal(key, value, replicaAddress);
    }
    
    // Internal methods with failover logic
    
    private ListenableFuture<VersionedValue> sendGetRequestWithFailover(String key, int attempt) {
        if (attempt >= maxRetries) {
            ListenableFuture<VersionedValue> failedFuture = new ListenableFuture<>();
            failedFuture.fail(new RuntimeException("Max retries exceeded for GET request"));
            return failedFuture;
        }
        
        NetworkAddress replica = selectNextReplica();
        ListenableFuture<VersionedValue> future = sendGetRequestInternal(key, replica);
        
        // Add failure handling for automatic retry
        future.onFailure(error -> {
            // Try next replica on failure
            sendGetRequestWithFailover(key, attempt + 1);
        });
        
        return future;
    }
    
    private ListenableFuture<Boolean> sendSetRequestWithFailover(String key, byte[] value, int attempt) {
        if (attempt >= maxRetries) {
            ListenableFuture<Boolean> failedFuture = new ListenableFuture<>();
            failedFuture.fail(new RuntimeException("Max retries exceeded for SET request"));
            return failedFuture;
        }
        
        NetworkAddress replica = selectNextReplica();
        ListenableFuture<Boolean> future = sendSetRequestInternal(key, value, replica);
        
        // Add failure handling for automatic retry
        future.onFailure(error -> {
            // Try next replica on failure
            sendSetRequestWithFailover(key, value, attempt + 1);
        });
        
        return future;
    }
    
    private ListenableFuture<VersionedValue> sendGetRequestInternal(String key, NetworkAddress replicaAddress) {
        String correlationId = generateCorrelationId();
        ListenableFuture<VersionedValue> future = new ListenableFuture<>();
        
        // Use RequestWaitingList to track the request
        requestWaitingList.add(correlationId, new RequestCallback<Object>() {
            @Override
            public void onResponse(Object response, NetworkAddress fromNode) {
                if (response instanceof GetResponse) {
                    GetResponse getResponse = (GetResponse) response;
                    // GetResponse is successful if we get a response; value can be null for "not found"
                    future.complete(getResponse.value());
                } else {
                    future.fail(new RuntimeException("Expected GetResponse but got: " + response.getClass()));
                }
            }
            
            @Override
            public void onError(Exception error) {
                future.fail(error);
            }
        });
        
        // Send client message for this request
        sendClientMessage(replicaAddress, MessageType.CLIENT_GET_REQUEST, new GetRequest(key), correlationId);

        return future;
    }
    
    private ListenableFuture<Boolean> sendSetRequestInternal(String key, byte[] value, NetworkAddress replicaAddress) {
        String correlationId = generateCorrelationId();
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        
        System.out.println("Client: Creating SET request - key: " + key + ", value: " + new String(value) + 
                          ", replica: " + replicaAddress + ", correlationId: " + correlationId);
        
        // Use RequestWaitingList to track the request
        requestWaitingList.add(correlationId, new RequestCallback<Object>() {
            @Override
            public void onResponse(Object response, NetworkAddress fromNode) {
                if (response instanceof SetResponse) {
                    SetResponse setResponse = (SetResponse) response;
                    future.complete(setResponse.success());
                } else {
                    future.fail(new RuntimeException("Expected SetResponse but got: " + response.getClass()));
                }
            }
            
            @Override
            public void onError(Exception error) {
                future.fail(error);
            }
        });
        
        // Send client message for this request
        sendClientMessage(replicaAddress, MessageType.CLIENT_SET_REQUEST, new SetRequest(key, value), correlationId);

        return future;
    }

    private void sendClientMessage(NetworkAddress destination, MessageType messageType, Object request, String correlationId) {
        // Register correlation handler that will route responses back to us
        messageBus.registerHandler(correlationId, this);
        
        byte[] payload = serializePayload(request);
        
        // Use dynamic client address registration
        if (currentClientAddress == null) {
            // Generate a unique client address for this request
            currentClientAddress = new NetworkAddress("127.0.0.1", 9000 + random.nextInt(1000));
        }
        
        Message message = new Message(currentClientAddress, destination, messageType, payload, correlationId);
        messageBus.sendMessage(message);
    }

    private NetworkAddress selectNextReplica() {
        if (knownReplicas.isEmpty()) {
            // Fallback to bootstrap replicas if no known replicas
            return bootstrapReplicas.get(replicaIndex % bootstrapReplicas.size());
        }
        
        List<NetworkAddress> replicaList = new ArrayList<>(knownReplicas);
        NetworkAddress selectedReplica = replicaList.get(replicaIndex % replicaList.size());
        replicaIndex = (replicaIndex + 1) % replicaList.size();
        return selectedReplica;
    }

    private void updateKnownReplicas(List<NetworkAddress> discoveredReplicas) {
        knownReplicas.addAll(discoveredReplicas);
        System.out.println("Client: Updated known replicas: " + knownReplicas.size() + " total");
    }

    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        System.out.println("Client: Received message " + message.messageType() + " from " + message.source() + 
                          " (correlationId=" + message.correlationId() + ")");
        
        // Verify this is a client response message
        if (message.messageType() != MessageType.CLIENT_GET_RESPONSE && 
            message.messageType() != MessageType.CLIENT_SET_RESPONSE) {
            System.out.println("Client: Ignoring non-client-response message: " + message.messageType());
            return;
        }
        
        String correlationId = message.correlationId();
        if (correlationId == null) {
            System.out.println("Client: Received message without correlation ID");
            return;
        }
        
        try {
            if (message.messageType() == MessageType.CLIENT_GET_RESPONSE) {
                // Deserialize as GetResponse
                GetResponse getResponse = deserializePayload(message.payload(), GetResponse.class);
                System.out.println("Client: Received GET response - key: " + getResponse.key() + 
                                 ", value: " + (getResponse.value() != null ? "found" : "not found") + ", correlationId: " + correlationId);
                
                // Route to RequestWaitingList
                requestWaitingList.handleResponse(correlationId, getResponse, message.source());
                
            } else if (message.messageType() == MessageType.CLIENT_SET_RESPONSE) {
                // Deserialize as SetResponse
                SetResponse setResponse = deserializePayload(message.payload(), SetResponse.class);
                System.out.println("Client: Received SET response - key: " + setResponse.key() + 
                                 ", success: " + setResponse.success() + ", correlationId: " + correlationId);
                
                // Route to RequestWaitingList
                requestWaitingList.handleResponse(correlationId, setResponse, message.source());
            }
            
        } catch (Exception ex) {
            System.out.println("Client: Failed to deserialize response payload: " + ex.getMessage());
            // Handle as error
            requestWaitingList.handleError(correlationId, 
                new RuntimeException("Failed to deserialize response", ex));
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

    private String generateCorrelationId() {
        return clientId + "-" + correlationIdGenerator.incrementAndGet();
    }

    private byte[] serializePayload(Object payload) {
        return messageCodec.encode(payload);
    }

    private <T> T deserializePayload(byte[] data, Class<T> type) {
        return messageCodec.decode(data, type);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Client)) return false;
        Client client = (Client) obj;
        return Objects.equals(clientId, client.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId);
    }

    @Override
    public String toString() {
        return "Client{id=" + clientId + ", replicas=" + knownReplicas.size() + "}";
    }
} 