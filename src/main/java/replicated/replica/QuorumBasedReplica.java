package replicated.replica;

import replicated.messaging.*;
import replicated.storage.*;
import replicated.future.ListenableFuture;
import java.util.*;

/**
 * Quorum-based replica implementation for distributed key-value store.
 * This implementation uses majority quorum consensus for read and write operations.
 */
public final class QuorumBasedReplica extends Replica {
    
    /**
     * Creates a QuorumBasedReplica with the specified configuration.
     */
    public QuorumBasedReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                             MessageBus messageBus, Storage storage, int requestTimeoutTicks) {
        super(name, networkAddress, peers, messageBus, storage, requestTimeoutTicks);
    }
    
    /**
     * Creates a QuorumBasedReplica with default timeout.
     */
    public QuorumBasedReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                             MessageBus messageBus, Storage storage) {
        super(name, networkAddress, peers, messageBus, storage, 10); // 10 tick default timeout
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
    
    @Override
    protected void sendTimeoutResponse(PendingRequest request) {
        if (request instanceof QuorumRequest quorumRequest) {
            if (quorumRequest.operation == QuorumRequest.Operation.GET) {
                GetResponse timeoutResponse = new GetResponse(quorumRequest.key, null);
                messageBus.sendMessage(new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(timeoutResponse)
                ));
            } else if (quorumRequest.operation == QuorumRequest.Operation.SET) {
                SetResponse timeoutResponse = new SetResponse(quorumRequest.key, false);
                messageBus.sendMessage(new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(timeoutResponse)
                ));
            }
        }
    }
    
    // Quorum-specific message handlers
    
    private void handleClientGetRequest(Message message) {
        String correlationId = generateRequestId();
        GetRequest clientRequest = deserializePayload(message.payload(), GetRequest.class);
        long timestamp = System.currentTimeMillis(); // In real system, use coordinated time
        
        QuorumRequest quorumRequest = new QuorumRequest(
            correlationId, message.source(), QuorumRequest.Operation.GET, 
            clientRequest.key(), null, timestamp, getCurrentTick()
        );
        
        pendingRequests.put(correlationId, quorumRequest);
        
        // Send INTERNAL_GET_REQUEST to all peers (including self)
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);
        
        for (NetworkAddress node : allNodes) {
            InternalGetRequest internalRequest = new InternalGetRequest(clientRequest.key(), correlationId);
            messageBus.sendMessage(new Message(
                networkAddress, node, MessageType.INTERNAL_GET_REQUEST,
                serializePayload(internalRequest)
            ));
        }
    }
    
    private void handleClientSetRequest(Message message) {
        String correlationId = generateRequestId();
        SetRequest clientRequest = deserializePayload(message.payload(), SetRequest.class);
        long timestamp = System.currentTimeMillis(); // In real system, use coordinated time
        VersionedValue value = new VersionedValue(clientRequest.value(), timestamp);
        
        QuorumRequest quorumRequest = new QuorumRequest(
            correlationId, message.source(), QuorumRequest.Operation.SET,
            clientRequest.key(), value, timestamp, getCurrentTick()
        );
        
        pendingRequests.put(correlationId, quorumRequest);
        
        // Send INTERNAL_SET_REQUEST to all peers (including self)
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);
        
        for (NetworkAddress node : allNodes) {
            InternalSetRequest internalRequest = new InternalSetRequest(
                clientRequest.key(), clientRequest.value(), timestamp, correlationId
            );
            messageBus.sendMessage(new Message(
                networkAddress, node, MessageType.INTERNAL_SET_REQUEST,
                serializePayload(internalRequest)
            ));
        }
    }
    
    private void handleInternalGetRequest(Message message) {
        InternalGetRequest getRequest = deserializePayload(message.payload(), InternalGetRequest.class);
        
        // Perform local storage operation
        ListenableFuture<VersionedValue> future = storage.get(getRequest.key().getBytes());
        
        future.onSuccess(value -> {
            InternalGetResponse response = new InternalGetResponse(getRequest.key(), value, getRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_GET_RESPONSE,
                serializePayload(response)
            ));
        }).onFailure(error -> {
            InternalGetResponse response = new InternalGetResponse(getRequest.key(), null, getRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_GET_RESPONSE,
                serializePayload(response)
            ));
        });
    }
    
    private void handleInternalSetRequest(Message message) {
        InternalSetRequest setRequest = deserializePayload(message.payload(), InternalSetRequest.class);
        VersionedValue value = new VersionedValue(setRequest.value(), setRequest.timestamp());
        
        // Perform local storage operation
        ListenableFuture<Boolean> future = storage.set(setRequest.key().getBytes(), value);
        
        future.onSuccess(success -> {
            InternalSetResponse response = new InternalSetResponse(setRequest.key(), success, setRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_SET_RESPONSE,
                serializePayload(response)
            ));
        }).onFailure(error -> {
            InternalSetResponse response = new InternalSetResponse(setRequest.key(), false, setRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_SET_RESPONSE,
                serializePayload(response)
            ));
        });
    }
    
    private void handleInternalGetResponse(Message message) {
        InternalGetResponse response = deserializePayload(message.payload(), InternalGetResponse.class);
        PendingRequest pending = pendingRequests.get(response.correlationId());
        
        if (pending instanceof QuorumRequest quorumRequest && 
            quorumRequest.operation == QuorumRequest.Operation.GET) {
            
            quorumRequest.addResponse(message.source(), response.value());
            
            if (quorumRequest.hasQuorum(calculateQuorumSize())) {
                // Send response to client with latest value
                VersionedValue latestValue = quorumRequest.getLatestValue();
                GetResponse clientResponse = new GetResponse(quorumRequest.key, latestValue);
                
                messageBus.sendMessage(new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse)
                ));
                
                pendingRequests.remove(response.correlationId());
            }
        }
    }
    
    private void handleInternalSetResponse(Message message) {
        InternalSetResponse response = deserializePayload(message.payload(), InternalSetResponse.class);
        PendingRequest pending = pendingRequests.get(response.correlationId());
        
        if (pending instanceof QuorumRequest quorumRequest && 
            quorumRequest.operation == QuorumRequest.Operation.SET) {
            
            quorumRequest.addResponse(message.source(), response.success());
            
            if (quorumRequest.hasQuorum(calculateQuorumSize())) {
                // Send response to client
                boolean success = quorumRequest.getSuccessCount() >= calculateQuorumSize();
                SetResponse clientResponse = new SetResponse(quorumRequest.key, success);
                
                messageBus.sendMessage(new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse)
                ));
                
                pendingRequests.remove(response.correlationId());
            }
        }
    }
    
    /**
     * Calculates the quorum size needed for consensus (majority).
     */
    private int calculateQuorumSize() {
        int totalNodes = peers.size() + 1; // peers + this replica
        return (totalNodes / 2) + 1; // majority
    }
    
    /**
     * Quorum-specific request tracking that extends the base PendingRequest.
     */
    private static class QuorumRequest extends PendingRequest {
        enum Operation { GET, SET }
        
        final Operation operation;
        final VersionedValue setValue; // For SET operations
        final long timestamp;
        
        private final Map<NetworkAddress, Object> responses = new HashMap<>();
        
        QuorumRequest(String requestId, NetworkAddress clientAddress, Operation operation,
                     String key, VersionedValue setValue, long timestamp, long startTick) {
            super(requestId, clientAddress, key, startTick);
            this.operation = operation;
            this.setValue = setValue;
            this.timestamp = timestamp;
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
} 