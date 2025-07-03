package replicated.replica;

import replicated.messaging.*;
import replicated.storage.*;
import replicated.future.ListenableFuture;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Replica implements MessageHandler {
    
    private final String name;
    private final NetworkAddress networkAddress;
    private final List<NetworkAddress> peers;
    private final MessageBus messageBus;
    private final Storage storage;
    private final int requestTimeoutTicks;
    
    // Request tracking
    private final AtomicLong requestIdGenerator = new AtomicLong(0);
    private final Map<String, QuorumState> pendingRequests = new HashMap<>();
    
    // Legacy constructor for backward compatibility
    public Replica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers) {
        this(name, networkAddress, peers, null, null, 10); // 10 tick default timeout
    }
    
    // Enhanced constructor for distributed functionality
    public Replica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers, 
                  MessageBus messageBus, Storage storage) {
        this(name, networkAddress, peers, messageBus, storage, 10); // 10 tick default timeout
    }
    
    // Full constructor with timeout configuration
    public Replica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
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
        
        // Validate dependencies for enhanced functionality
        if (messageBus != null && storage == null) {
            throw new IllegalArgumentException("Storage cannot be null when MessageBus is provided");
        }
        if (storage != null && messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null when Storage is provided");
        }
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
    
    @Override
    public void onMessageReceived(Message message) {
        if (messageBus == null || storage == null) {
            // Skip message processing if not fully configured
            return;
        }
        
        switch (message.messageType()) {
            case CLIENT_GET_REQUEST -> handleClientGetRequest(message);
            case CLIENT_SET_REQUEST -> handleClientSetRequest(message);
            case INTERNAL_GET_REQUEST -> handleInternalGetRequest(message);
            case INTERNAL_SET_REQUEST -> handleInternalSetRequest(message);
            case INTERNAL_GET_RESPONSE -> handleInternalGetResponse(message);
            case INTERNAL_SET_RESPONSE -> handleInternalSetResponse(message);
            default -> {
                // Unknown message type, ignore
            }
        }
    }
    
    /**
     * Called by the simulation loop for each tick.
     * This is where the replica can perform proactive work like
     * sending heartbeats, timing out requests, etc.
     * 
     * @param currentTick the current simulation tick
     */
    public void tick(long currentTick) {
        if (messageBus == null || storage == null) {
            return;
        }
        
        // Handle request timeouts
        List<String> timedOutRequests = new ArrayList<>();
        for (Map.Entry<String, QuorumState> entry : pendingRequests.entrySet()) {
            QuorumState state = entry.getValue();
            if (currentTick - state.startTick >= requestTimeoutTicks) {
                timedOutRequests.add(entry.getKey());
                
                // Send timeout response to client
                sendTimeoutResponse(state);
            }
        }
        
        // Clean up timed out requests
        for (String requestId : timedOutRequests) {
            pendingRequests.remove(requestId);
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
    
    // Message handler methods
    
    private void handleClientGetRequest(Message message) {
        String requestId = generateRequestId();
        long timestamp = System.currentTimeMillis(); // In real system, use coordinated time
        
        QuorumState quorumState = new QuorumState(
            requestId, message.source(), QuorumState.Operation.GET, 
            extractKey(message), null, timestamp, getCurrentTick()
        );
        
        pendingRequests.put(requestId, quorumState);
        
        // Send INTERNAL_GET_REQUEST to all peers (including self)
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);
        
        for (NetworkAddress node : allNodes) {
            GetRequest internalRequest = new GetRequest(extractKey(message), requestId);
            messageBus.sendMessage(new Message(
                networkAddress, node, MessageType.INTERNAL_GET_REQUEST,
                serializePayload(internalRequest)
            ));
        }
    }
    
    private void handleClientSetRequest(Message message) {
        String requestId = generateRequestId();
        SetRequest setRequest = deserializePayload(message.payload(), SetRequest.class);
        VersionedValue value = new VersionedValue(setRequest.value(), setRequest.timestamp());
        
        QuorumState quorumState = new QuorumState(
            requestId, message.source(), QuorumState.Operation.SET,
            setRequest.key(), value, setRequest.timestamp(), getCurrentTick()
        );
        
        pendingRequests.put(requestId, quorumState);
        
        // Send INTERNAL_SET_REQUEST to all peers (including self)
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);
        
        for (NetworkAddress node : allNodes) {
            SetRequest internalRequest = new SetRequest(
                setRequest.key(), setRequest.value(), setRequest.timestamp(), requestId
            );
            messageBus.sendMessage(new Message(
                networkAddress, node, MessageType.INTERNAL_SET_REQUEST,
                serializePayload(internalRequest)
            ));
        }
    }
    
    private void handleInternalGetRequest(Message message) {
        GetRequest getRequest = deserializePayload(message.payload(), GetRequest.class);
        
        // Perform local storage operation
        ListenableFuture<VersionedValue> future = storage.get(getRequest.key().getBytes());
        
        future.onSuccess(value -> {
            GetResponse response = new GetResponse(getRequest.key(), value, getRequest.requestId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_GET_RESPONSE,
                serializePayload(response)
            ));
        }).onFailure(error -> {
            GetResponse response = new GetResponse(getRequest.key(), null, getRequest.requestId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_GET_RESPONSE,
                serializePayload(response)
            ));
        });
    }
    
    private void handleInternalSetRequest(Message message) {
        SetRequest setRequest = deserializePayload(message.payload(), SetRequest.class);
        VersionedValue value = new VersionedValue(setRequest.value(), setRequest.timestamp());
        
        // Perform local storage operation
        ListenableFuture<Boolean> future = storage.set(setRequest.key().getBytes(), value);
        
        future.onSuccess(success -> {
            SetResponse response = new SetResponse(setRequest.key(), success, setRequest.requestId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_SET_RESPONSE,
                serializePayload(response)
            ));
        }).onFailure(error -> {
            SetResponse response = new SetResponse(setRequest.key(), false, setRequest.requestId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_SET_RESPONSE,
                serializePayload(response)
            ));
        });
    }
    
    private void handleInternalGetResponse(Message message) {
        GetResponse response = deserializePayload(message.payload(), GetResponse.class);
        QuorumState state = pendingRequests.get(response.requestId());
        
        if (state != null && state.operation == QuorumState.Operation.GET) {
            state.addResponse(message.source(), response.value());
            
            if (state.hasQuorum(calculateQuorumSize())) {
                // Send response to client with latest value
                VersionedValue latestValue = state.getLatestValue();
                GetResponse clientResponse = new GetResponse(state.key, latestValue, null);
                
                messageBus.sendMessage(new Message(
                    networkAddress, state.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse)
                ));
                
                pendingRequests.remove(response.requestId());
            }
        }
    }
    
    private void handleInternalSetResponse(Message message) {
        SetResponse response = deserializePayload(message.payload(), SetResponse.class);
        QuorumState state = pendingRequests.get(response.requestId());
        
        if (state != null && state.operation == QuorumState.Operation.SET) {
            state.addResponse(message.source(), response.success());
            
            if (state.hasQuorum(calculateQuorumSize())) {
                // Send response to client
                boolean success = state.getSuccessCount() >= calculateQuorumSize();
                SetResponse clientResponse = new SetResponse(state.key, success, null);
                
                messageBus.sendMessage(new Message(
                    networkAddress, state.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse)
                ));
                
                pendingRequests.remove(response.requestId());
            }
        }
    }
    
    // Helper methods
    
    private String generateRequestId() {
        return name + "-" + requestIdGenerator.incrementAndGet();
    }
    
    private int calculateQuorumSize() {
        int totalNodes = peers.size() + 1; // peers + this replica
        return (totalNodes / 2) + 1; // majority
    }
    
    private long getCurrentTick() {
        // In a real implementation, this would come from the simulation
        return System.currentTimeMillis() / 1000; // Simple tick approximation
    }
    
    private String extractKey(Message message) {
        try {
            if (message.messageType() == MessageType.CLIENT_GET_REQUEST) {
                GetRequest request = deserializePayload(message.payload(), GetRequest.class);
                return request.key();
            } else if (message.messageType() == MessageType.CLIENT_SET_REQUEST) {
                SetRequest request = deserializePayload(message.payload(), SetRequest.class);
                return request.key();
            }
        } catch (Exception e) {
            // Handle deserialization errors
        }
        return "unknown";
    }
    
    private void sendTimeoutResponse(QuorumState state) {
        if (state.operation == QuorumState.Operation.GET) {
            GetResponse timeoutResponse = new GetResponse(state.key, null, null);
            messageBus.sendMessage(new Message(
                networkAddress, state.clientAddress, MessageType.CLIENT_RESPONSE,
                serializePayload(timeoutResponse)
            ));
        } else if (state.operation == QuorumState.Operation.SET) {
            SetResponse timeoutResponse = new SetResponse(state.key, false, null);
            messageBus.sendMessage(new Message(
                networkAddress, state.clientAddress, MessageType.CLIENT_RESPONSE,
                serializePayload(timeoutResponse)
            ));
        }
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
    
    // QuorumState class for tracking pending requests
    
    private static class QuorumState {
        enum Operation { GET, SET }
        
        final String requestId;
        final NetworkAddress clientAddress;
        final Operation operation;
        final String key;
        final VersionedValue setValue; // For SET operations
        final long timestamp;
        final long startTick;
        
        private final Map<NetworkAddress, Object> responses = new HashMap<>();
        
        QuorumState(String requestId, NetworkAddress clientAddress, Operation operation,
                   String key, VersionedValue setValue, long timestamp, long startTick) {
            this.requestId = requestId;
            this.clientAddress = clientAddress;
            this.operation = operation;
            this.key = key;
            this.setValue = setValue;
            this.timestamp = timestamp;
            this.startTick = startTick;
        }
        
        void addResponse(NetworkAddress source, Object response) {
            responses.put(source, response);
        }
        
        boolean hasQuorum(int quorumSize) {
            return responses.size() >= quorumSize;
        }
        
        VersionedValue getLatestValue() {
            VersionedValue latest = null;
            for (Object response : responses.values()) {
                if (response instanceof VersionedValue value) {
                    if (latest == null || value.timestamp() > latest.timestamp()) {
                        latest = value;
                    }
                }
            }
            return latest;
        }
        
        int getSuccessCount() {
            int count = 0;
            for (Object response : responses.values()) {
                if (response instanceof Boolean success && success) {
                    count++;
                }
            }
            return count;
        }
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