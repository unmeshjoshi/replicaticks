package replicated.client;

import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.storage.VersionedValue;
import replicated.future.ListenableFuture;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Client implements MessageHandler {
    
    private final MessageBus messageBus;
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
    
    // Internal counter for timeout management (TigerBeetle pattern)
    private long currentTick = 0;
    
    /**
     * Creates a Client with bootstrap replicas for cluster discovery.
     * Follows the Kafka bootstrap pattern - client starts with one or more replica addresses
     * and discovers the full cluster topology dynamically.
     * 
     * @param messageBus the message bus for communication
     * @param bootstrapReplicas initial list of replica addresses for cluster discovery
     */
    public Client(MessageBus messageBus, List<NetworkAddress> bootstrapReplicas) {
        this(messageBus, bootstrapReplicas, 10);
    }
    
    /**
     * Full constructor with configurable timeout.
     */
    public Client(MessageBus messageBus, List<NetworkAddress> bootstrapReplicas, int requestTimeoutTicks) {
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
        
        // Initialize known replicas with bootstrap replicas
        this.knownReplicas.addAll(bootstrapReplicas);
        
        // Don't register upfront - register dynamically when sending messages
    }
    
    // Backward compatibility constructor (for tests)
    public Client(MessageBus messageBus) {
        // Create a single bootstrap replica for backward compatibility
        this(messageBus, List.of(new NetworkAddress("127.0.0.1", 8080)));
    }
    
    public String getClientId() {
        return clientId;
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
        
        // Track the pending request
        PendingRequest pendingRequest = new PendingRequest(
            correlationId, PendingRequest.Type.GET, key, future, getCurrentTick()
        );
        pendingRequests.put(correlationId, pendingRequest);
        
        // Establish connection for this request
        establishConnectionAndSend(replicaAddress, MessageType.CLIENT_GET_REQUEST, new GetRequest(key));
        
        return future;
    }
    
    private ListenableFuture<Boolean> sendSetRequestInternal(String key, byte[] value, NetworkAddress replicaAddress) {
        String correlationId = generateCorrelationId();
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        
        // Track the pending request
        PendingRequest pendingRequest = new PendingRequest(
            correlationId, PendingRequest.Type.SET, key, future, getCurrentTick()
        );
        pendingRequests.put(correlationId, pendingRequest);
        
        // Establish connection for this request
        establishConnectionAndSend(replicaAddress, MessageType.CLIENT_SET_REQUEST, new SetRequest(key, value));
        
        return future;
    }
    
    /**
     * Establishes connection and sends message - following the pattern of creating
     * connections per request rather than per-client registration.
     * 
     * This method:
     * 1. Establishes the connection first via the network layer
     * 2. Gets the actual local address assigned by the network
     * 3. Registers the handler with that real address
     * 4. Sends the message
     */
    private void establishConnectionAndSend(NetworkAddress destination, MessageType messageType, Object request) {
        // Step 1: Establish connection through network layer and get actual local address
        NetworkAddress actualClientAddress = establishConnectionAndGetLocalAddress(destination);
        
        // Step 2: Register handler with the actual connection address
        messageBus.registerHandler(actualClientAddress, this);
        this.currentClientAddress = actualClientAddress;
        
        // Step 3: Send the message using the established connection
        Message message = new Message(actualClientAddress, destination, messageType, serializePayload(request));
        messageBus.sendMessage(message);
    }
    
    /**
     * Establishes connection with the destination and returns the actual local address
     * assigned by the network layer. This simulates the OS assigning an ephemeral port.
     */
    private NetworkAddress establishConnectionAndGetLocalAddress(NetworkAddress destination) {
        // In a real implementation, this would:
        // 1. Create a socket connection to the destination
        // 2. Get the local address/port assigned by the OS
        // 3. Return that actual address
        
        // For our simulation, we'll ask the network layer to establish the connection
        // and return the ephemeral address it assigns
        return messageBus.establishConnection(destination);
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
        
        try {
            // Try to determine if it's a GET or SET response by attempting deserialization
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
     * Handles request timeouts and cleanup.
     * Client implementations manage their own internal tick counters for timeout management.
     */
    public void tick() {
        currentTick++; // Increment internal counter (TigerBeetle pattern)
        
        // Handle request timeouts
        List<String> timedOutRequests = new ArrayList<>();
        
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest request = entry.getValue();
            if (currentTick - request.startTick >= requestTimeoutTicks) {
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
    
    private long getCurrentTick() {
        // In a real implementation, this would come from the simulation
        return System.currentTimeMillis() / 1000; // Simple tick approximation
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
        final long startTick;
        
        PendingRequest(String correlationId, Type type, String key, ListenableFuture<?> future, long startTick) {
            this.correlationId = correlationId;
            this.type = type;
            this.key = key;
            this.future = future;
            this.startTick = startTick;
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