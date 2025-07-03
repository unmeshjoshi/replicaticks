package replicated.client;

import replicated.messaging.*;
import replicated.storage.VersionedValue;
import replicated.future.ListenableFuture;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Client implements MessageHandler {
    
    private final NetworkAddress address;
    private final MessageBus messageBus;
    private final int requestTimeoutTicks;
    
    // Request tracking
    private final AtomicLong correlationIdGenerator = new AtomicLong(0);
    private final Map<String, PendingRequest> pendingRequests = new HashMap<>();
    
    // Default constructor with 10 tick timeout
    public Client(NetworkAddress address, MessageBus messageBus) {
        this(address, messageBus, 10);
    }
    
    // Full constructor with configurable timeout
    public Client(NetworkAddress address, MessageBus messageBus, int requestTimeoutTicks) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        if (messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null");
        }
        
        this.address = address;
        this.messageBus = messageBus;
        this.requestTimeoutTicks = requestTimeoutTicks;
    }
    
    public NetworkAddress getAddress() {
        return address;
    }
    
    /**
     * Send a GET request to a replica and return a future for the response.
     */
    public ListenableFuture<VersionedValue> sendGetRequest(String key, NetworkAddress replicaAddress) {
        String correlationId = generateCorrelationId();
        ListenableFuture<VersionedValue> future = new ListenableFuture<>();
        
        // Track the pending request
        PendingRequest pendingRequest = new PendingRequest(
            correlationId, PendingRequest.Type.GET, key, future, getCurrentTick()
        );
        pendingRequests.put(correlationId, pendingRequest);
        
        // Send the request
        GetRequest request = new GetRequest(key);
        Message message = new Message(
            address, replicaAddress, MessageType.CLIENT_GET_REQUEST,
            serializePayload(request)
        );
        messageBus.sendMessage(message);
        
        return future;
    }
    
    /**
     * Send a SET request to a replica and return a future for the response.
     */
    public ListenableFuture<Boolean> sendSetRequest(String key, byte[] value, NetworkAddress replicaAddress) {
        String correlationId = generateCorrelationId();
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        
        // Track the pending request
        PendingRequest pendingRequest = new PendingRequest(
            correlationId, PendingRequest.Type.SET, key, future, getCurrentTick()
        );
        pendingRequests.put(correlationId, pendingRequest);
        
        // Send the request
        SetRequest request = new SetRequest(key, value);
        Message message = new Message(
            address, replicaAddress, MessageType.CLIENT_SET_REQUEST,
            serializePayload(request)
        );
        messageBus.sendMessage(message);
        
        return future;
    }
    
    @Override
    public void onMessageReceived(Message message) {
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
     */
    public void tick(long currentTick) {
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
        return "client-" + address.ipAddress() + "-" + address.port() + "-" + correlationIdGenerator.incrementAndGet();
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
        return Objects.equals(address, client.address);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(address);
    }
    
    @Override
    public String toString() {
        return "Client{" +
                "address=" + address +
                ", pendingRequests=" + pendingRequests.size() +
                '}';
    }
} 