package replicated.client;

import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.storage.VersionedValue;
import replicated.future.ListenableFuture;
import replicated.util.Timeout;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Client implements MessageHandler {
    
    private final BaseMessageBus messageBus;
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
    
    // Request tracking
    private final AtomicLong correlationIdGenerator = new AtomicLong(0);
    private final Map<String, PendingRequest> pendingRequests = new HashMap<>();
    
    // Connection pool rotation index
    private int replicaIndex = 0;
    
    // Timeout management using TigerBeetle-style Timeout class
    private final Timeout timeout;
    
    /**
     * Creates a Client with bootstrap replicas for cluster discovery.
     * Follows the Kafka bootstrap pattern - client starts with one or more replica addresses
     * and discovers the full cluster topology dynamically.
     * 
     * @param messageBus the message bus for communication
     * @param bootstrapReplicas initial list of replica addresses for cluster discovery
     */
    public Client(BaseMessageBus messageBus, List<NetworkAddress> bootstrapReplicas) {
        this(messageBus, bootstrapReplicas, 10);
    }
    
    /**
     * Full constructor with configurable timeout.
     */
    public Client(BaseMessageBus messageBus, List<NetworkAddress> bootstrapReplicas, int requestTimeoutTicks) {
        if (messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null");
        }
        if (bootstrapReplicas == null || bootstrapReplicas.isEmpty()) {
            throw new IllegalArgumentException("Bootstrap replicas cannot be null or empty");
        }
        
        this.messageBus = messageBus;
        this.bootstrapReplicas = new ArrayList<>(bootstrapReplicas);
        this.requestTimeoutTicks = requestTimeoutTicks;
        this.clientId = "client-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Initialize timeout management
        this.timeout = new Timeout("client-request-timeout", requestTimeoutTicks);
        
        // Initialize known replicas with bootstrap replicas
        this.knownReplicas.addAll(bootstrapReplicas);
        
        // Don't register upfront - register dynamically when sending messages
    }
    
    // Backward compatibility constructor (for tests)
    public Client(BaseMessageBus messageBus) {
        // Create a single bootstrap replica for backward compatibility
        this(messageBus, List.of(new NetworkAddress("127.0.0.1", 8080)));
    }
    
    public String getClientId() {
        return clientId;
    }
    
    /**
     * Gets the message bus used by this client.
     * @return the message bus instance
     */
    public BaseMessageBus getMessageBus() {
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
        
        // Track the pending request with timeout
        Timeout requestTimeout = new Timeout("get-request-" + correlationId, requestTimeoutTicks);
        requestTimeout.start();
        PendingRequest pendingRequest = new PendingRequest(
            correlationId, PendingRequest.Type.GET, key, future, requestTimeout
        );
        pendingRequests.put(correlationId, pendingRequest);
        
        // Establish connection for this request
        establishConnectionAndSend(replicaAddress, MessageType.CLIENT_GET_REQUEST, new GetRequest(key), correlationId);

        return future;
    }
    
    private ListenableFuture<Boolean> sendSetRequestInternal(String key, byte[] value, NetworkAddress replicaAddress) {
        String correlationId = generateCorrelationId();
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        
        System.out.println("Client: Creating SET request - key: " + key + ", value: " + new String(value) + 
                          ", replica: " + replicaAddress + ", correlationId: " + correlationId);
        
        // Track the pending request with timeout
        Timeout requestTimeout = new Timeout("set-request-" + correlationId, requestTimeoutTicks);
        requestTimeout.start();
        PendingRequest pendingRequest = new PendingRequest(
            correlationId, PendingRequest.Type.SET, key, future, requestTimeout
        );
        pendingRequests.put(correlationId, pendingRequest);
        
        System.out.println("Client: SET request registered with pendingRequests, size: " + pendingRequests.size());
        
        // Establish connection for this request
        System.out.println("Client: Establishing connection and sending SET request...");
        establishConnectionAndSend(replicaAddress, MessageType.CLIENT_SET_REQUEST, new SetRequest(key, value), correlationId);
        
        System.out.println("Client: SET request sent successfully");
        return future;
    }
    
    /**
     * Establishes connection and sends message - following the pattern of creating
     * connections per request rather than per-client registration.
     * 
     * This method:
     * 1. Registers the client with MessageBus to receive responses
     * 2. Registers the client as a correlation ID handler for this specific request
     * 3. Gets the actual local address assigned by the network
     * 4. Sends the message
     */
    private void establishConnectionAndSend(NetworkAddress destination, MessageType messageType, Object request, String correlationId) {
        System.out.println("Client: establishConnectionAndSend - destination: " + destination + 
                          ", messageType: " + messageType + ", correlationId: " + correlationId);
        
        // Step 1: Register client with MessageBus to receive responses
        System.out.println("Client: Registering client with MessageBus...");
        NetworkAddress actualClientAddress = messageBus.registerClient(this);
        System.out.println("Client: Registered with actual client address: " + actualClientAddress);
        
        // Step 2: Register client as correlation ID handler for this request
        System.out.println("Client: Registering correlation ID handler for: " + correlationId);
        messageBus.registerCorrelationIdHandler(correlationId, this);
        
        // Step 3: Update current client address
        this.currentClientAddress = actualClientAddress;
        
        // Step 4: Send the message using the established connection
        System.out.println("Client: Creating message from " + actualClientAddress + " to " + destination);
        Message message = new Message(actualClientAddress, destination, messageType, serializePayload(request), correlationId);
        System.out.println("Client: Sending message via MessageBus...");
        messageBus.sendMessage(message);
        System.out.println("Client: Message sent via MessageBus");
    }
    

    
    /**
     * Selects the next replica using round-robin with randomization.
     * Implements connection pool pattern similar to Kafka clients.
     */
    private NetworkAddress selectNextReplica() {
        List<NetworkAddress> replicas = new ArrayList<>(knownReplicas);
        if (replicas.isEmpty()) {
            throw new RuntimeException("No known replicas available");
        }
        
        // Round-robin with randomization
        NetworkAddress selected = replicas.get(replicaIndex % replicas.size());
        replicaIndex = (replicaIndex + 1) % replicas.size();
        
        return selected;
    }
    
    /**
     * Discovers additional replicas from cluster responses.
     * This simulates how Kafka clients learn cluster topology after connecting.
     */
    private void updateKnownReplicas(List<NetworkAddress> discoveredReplicas) {
        if (discoveredReplicas != null) {
            knownReplicas.addAll(discoveredReplicas);
        }
    }
    
    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        if (message.messageType() != MessageType.CLIENT_RESPONSE) {
            // Only handle client responses
            return;
        }
        
        String correlationId = message.correlationId();
        if (correlationId == null) {
            // Fallback to old key-based matching for backward compatibility
            handleResponseByKey(message);
            return;
        }
        
        // Handle response by correlation ID
        PendingRequest pendingRequest = pendingRequests.get(correlationId);
        if (pendingRequest == null) {
            // No matching request found, ignore
            System.out.println("Client: No matching request found for correlationId: " + correlationId);
            return;
        }
        
        try {
            byte[] payload = message.payload();
            
            if (pendingRequest.type == PendingRequest.Type.GET) {
                GetResponse getResponse = deserializePayload(payload, GetResponse.class);
                @SuppressWarnings("unchecked")
                ListenableFuture<VersionedValue> future = (ListenableFuture<VersionedValue>) pendingRequest.future;
                
                // Debug logging for GET response
                String key = getResponse.key();
                VersionedValue value = getResponse.value();
                String valueStr = value != null ? new String(value.value()) : "null";
                System.out.println("Client: Received GET response - correlationId: " + correlationId + 
                                  ", key: " + key + ", value: " + valueStr);
                
                future.complete(value);
            } else if (pendingRequest.type == PendingRequest.Type.SET) {
                SetResponse setResponse = deserializePayload(payload, SetResponse.class);
                @SuppressWarnings("unchecked")
                ListenableFuture<Boolean> future = (ListenableFuture<Boolean>) pendingRequest.future;
                
                // Debug logging for SET response
                String key = setResponse.key();
                boolean success = setResponse.success();
                System.out.println("Client: Received SET response - correlationId: " + correlationId + 
                                  ", key: " + key + ", success: " + success);
                
                future.complete(success);
            }
            
            // Remove the completed request and unregister the correlation ID handler
            pendingRequests.remove(correlationId);
            messageBus.unregisterCorrelationIdHandler(correlationId);
            
        } catch (Exception e) {
            // Failed to handle response, complete with error
            System.out.println("Client: Failed to deserialize response for correlationId: " + correlationId + 
                              ", error: " + e.getMessage());
            pendingRequest.future.fail(new RuntimeException("Failed to deserialize response", e));
            pendingRequests.remove(correlationId);
            messageBus.unregisterCorrelationIdHandler(correlationId);
        }
    }
    
    /**
     * Fallback method for handling responses by key (for backward compatibility).
     */
    private void handleResponseByKey(Message message) {
        try {
            byte[] payload = message.payload();
            
            // Try GET response first
            try {
                GetResponse getResponse = deserializePayload(payload, GetResponse.class);
                handleGetResponse(getResponse);
                return;
            } catch (Exception e) {
                // Not a GET response, try SET response
            }
            
            // Try SET response
            try {
                SetResponse setResponse = deserializePayload(payload, SetResponse.class);
                handleSetResponse(setResponse);
                return;
            } catch (Exception e) {
                // Not a SET response either, ignore
            }
            
        } catch (Exception e) {
            // Failed to handle response, log or ignore
        }
    }
    
    /**
     * Called by the simulation loop for each tick.
     * Handles request timeouts and cleanup using Timeout objects.
     */
    public void tick() {
        // Tick the main timeout object
        timeout.tick();
        
        // Handle request timeouts
        List<String> timedOutRequests = new ArrayList<>();
        
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest request = entry.getValue();
            request.timeout.tick(); // Tick each request's timeout
            
            if (request.timeout.fired()) {
                timedOutRequests.add(entry.getKey());
                
                // Complete the future with timeout error
                request.future.fail(new RuntimeException("Request timeout after " + requestTimeoutTicks + " ticks"));
            }
        }
        
        // Clean up timed out requests
        for (String correlationId : timedOutRequests) {
            pendingRequests.remove(correlationId);
        }
    }
    
    // Private helper methods
    
    private void handleGetResponse(GetResponse response) {
        // Find matching request by key (since client responses don't have correlation ID)
        String matchingCorrelationId = findPendingGetRequest(response.key());
        
        if (matchingCorrelationId != null) {
            PendingRequest pendingRequest = pendingRequests.remove(matchingCorrelationId);
            if (pendingRequest != null && pendingRequest.type == PendingRequest.Type.GET) {
                @SuppressWarnings("unchecked")
                ListenableFuture<VersionedValue> future = (ListenableFuture<VersionedValue>) pendingRequest.future;
                future.complete(response.value());
            }
        }
    }
    
    private void handleSetResponse(SetResponse response) {
        // Find matching request by key
        String matchingCorrelationId = findPendingSetRequest(response.key());
        
        if (matchingCorrelationId != null) {
            PendingRequest pendingRequest = pendingRequests.remove(matchingCorrelationId);
            if (pendingRequest != null && pendingRequest.type == PendingRequest.Type.SET) {
                @SuppressWarnings("unchecked")
                ListenableFuture<Boolean> future = (ListenableFuture<Boolean>) pendingRequest.future;
                future.complete(response.success());
            }
        }
    }
    
    private String findPendingGetRequest(String key) {
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest request = entry.getValue();
            if (request.type == PendingRequest.Type.GET && Objects.equals(request.key, key)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private String findPendingSetRequest(String key) {
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest request = entry.getValue();
            if (request.type == PendingRequest.Type.SET && Objects.equals(request.key, key)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private String generateCorrelationId() {
        return clientId + "-" + correlationIdGenerator.incrementAndGet();
    }
    

    
    private byte[] serializePayload(Object payload) {
        try {
            return JsonMessageCodec.createConfiguredObjectMapper().writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }
    
    private <T> T deserializePayload(byte[] data, Class<T> type) {
        try {
            return JsonMessageCodec.createConfiguredObjectMapper().readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize payload", e);
        }
    }
    
    // Inner class for tracking pending requests
    
    private static class PendingRequest {
        enum Type { GET, SET }
        
        final String correlationId;
        final Type type;
        final String key;
        final ListenableFuture<?> future;
        final Timeout timeout;
        
        PendingRequest(String correlationId, Type type, String key, ListenableFuture<?> future, Timeout timeout) {
            this.correlationId = correlationId;
            this.type = type;
            this.key = key;
            this.future = future;
            this.timeout = timeout;
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Client client = (Client) obj;
        return Objects.equals(clientId, client.clientId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(clientId);
    }
    
    @Override
    public String toString() {
        return "Client{" +
                "clientId='" + clientId + '\'' +
                ", pendingRequests=" + pendingRequests.size() +
                '}';
    }
} 