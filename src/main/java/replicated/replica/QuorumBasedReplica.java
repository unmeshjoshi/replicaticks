package replicated.replica;

import replicated.messaging.*;
import replicated.storage.*;
import replicated.future.ListenableFuture;
import replicated.util.Timeout;
import java.util.*;
import replicated.network.MessageContext; // Added import

/**
 * Quorum-based replica implementation for distributed key-value store.
 * This implementation uses majority quorum consensus for read and write operations.
 */
public final class QuorumBasedReplica extends Replica {
    
    /**
     * Creates a QuorumBasedReplica with the specified configuration.
     */
    public QuorumBasedReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                             BaseMessageBus messageBus, Storage storage, int requestTimeoutTicks) {
        super(name, networkAddress, peers, messageBus, storage, requestTimeoutTicks);
    }
    
    /**
     * Creates a QuorumBasedReplica with default timeout.
     */
    public QuorumBasedReplica(String name, NetworkAddress networkAddress, List<NetworkAddress> peers,
                             BaseMessageBus messageBus, Storage storage) {
        super(name, networkAddress, peers, messageBus, storage, 10); // 10 tick default timeout
    }
    
    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        if (messageBus == null || storage == null) {
            // Skip message processing if not fully configured
            return;
        }
        
        switch (message.messageType()) {
            case CLIENT_GET_REQUEST -> handleClientGetRequest(message, ctx);
            case CLIENT_SET_REQUEST -> handleClientSetRequest(message, ctx);
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
                Message clientResponse = new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(timeoutResponse), quorumRequest.requestId
                );
                if (quorumRequest.responseContext != null) {
                    messageBus.reply(quorumRequest.responseContext, clientResponse);
                } else {
                    messageBus.sendMessage(clientResponse);
                }
            } else if (quorumRequest.operation == QuorumRequest.Operation.SET) {
                SetResponse timeoutResponse = new SetResponse(quorumRequest.key, false);
                Message clientResponse = new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(timeoutResponse), quorumRequest.requestId
                );
                if (quorumRequest.responseContext != null) {
                    messageBus.reply(quorumRequest.responseContext, clientResponse);
                } else {
                    messageBus.sendMessage(clientResponse);
                }
            }
        }
    }
    
    /**
     * Generates a unique correlation ID for internal messages.
     */
    private String generateCorrelationId() {
        return "internal-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }
    
    // Quorum-specific message handlers
    
    private void handleClientGetRequest(Message message, MessageContext ctx) {
        String correlationId = message.correlationId();
        GetRequest clientRequest = deserializePayload(message.payload(), GetRequest.class);
        long timestamp = System.currentTimeMillis(); // In real system, use coordinated time
        
        System.out.println("QuorumBasedReplica: Processing client GET request - key: " + clientRequest.key() + 
                          ", correlationId: " + correlationId + ", from: " + message.source());
        
        // Create timeout for this request
        Timeout requestTimeout = new Timeout("quorum-get-" + correlationId, requestTimeoutTicks);
        requestTimeout.start();
        
        QuorumRequest quorumRequest = new QuorumRequest(
            correlationId, message.source(), QuorumRequest.Operation.GET, 
            clientRequest.key(), null, timestamp, requestTimeout
        );
        quorumRequest.responseContext = ctx;
        pendingRequests.put(correlationId, quorumRequest);
        
        // Send INTERNAL_GET_REQUEST to all peers (including self)
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);
        
        for (NetworkAddress node : allNodes) {
            String internalCorrelationId = generateCorrelationId();
            // Track the internal correlation ID for this request
            quorumRequest.addInternalCorrelationId(internalCorrelationId);
            
            InternalGetRequest internalRequest = new InternalGetRequest(clientRequest.key(), internalCorrelationId);
            messageBus.sendMessage(new Message(
                networkAddress, node, MessageType.INTERNAL_GET_REQUEST,
                serializePayload(internalRequest), internalCorrelationId
            ));
        }
    }
    
    private void handleClientSetRequest(Message message, MessageContext ctx) {
        String correlationId = message.correlationId();
        SetRequest clientRequest = deserializePayload(message.payload(), SetRequest.class);
        long timestamp = System.currentTimeMillis(); // In real system, use coordinated time
        VersionedValue value = new VersionedValue(clientRequest.value(), timestamp);
        
        System.out.println("QuorumBasedReplica: Processing client SET request - key: " + clientRequest.key() + 
                          ", value: " + new String(clientRequest.value()) + ", correlationId: " + correlationId + 
                          ", from: " + message.source());
        
        // Create timeout for this request
        Timeout requestTimeout = new Timeout("quorum-set-" + correlationId, requestTimeoutTicks);
        requestTimeout.start();
        
        QuorumRequest quorumRequest = new QuorumRequest(
            correlationId, message.source(), QuorumRequest.Operation.SET,
            clientRequest.key(), value, timestamp, requestTimeout
        );
        quorumRequest.responseContext = ctx;
        pendingRequests.put(correlationId, quorumRequest);
        
        // Send INTERNAL_SET_REQUEST to all peers (including self)
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);
        
        for (NetworkAddress node : allNodes) {
            String internalCorrelationId = generateCorrelationId();
            // Track the internal correlation ID for this request
            quorumRequest.addInternalCorrelationId(internalCorrelationId);
            
            InternalSetRequest internalRequest = new InternalSetRequest(
                clientRequest.key(), clientRequest.value(), timestamp, internalCorrelationId
            );
            messageBus.sendMessage(new Message(
                networkAddress, node, MessageType.INTERNAL_SET_REQUEST,
                serializePayload(internalRequest), internalCorrelationId
            ));
        }
    }
    
    private void handleInternalGetRequest(Message message) {
        InternalGetRequest getRequest = deserializePayload(message.payload(), InternalGetRequest.class);
        
        System.out.println("QuorumBasedReplica: Processing internal GET request - key: " + getRequest.key() + 
                          ", correlationId: " + getRequest.correlationId() + ", from: " + message.source());
        
        // Perform local storage operation
        ListenableFuture<VersionedValue> future = storage.get(getRequest.key().getBytes());
        
        future.onSuccess(value -> {
            String valueStr = value != null ? new String(value.value()) : "null";
            System.out.println("QuorumBasedReplica: Internal GET completed - key: " + getRequest.key() + 
                              ", value: " + valueStr + ", correlationId: " + getRequest.correlationId());
            
            InternalGetResponse response = new InternalGetResponse(getRequest.key(), value, getRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_GET_RESPONSE,
                serializePayload(response), getRequest.correlationId()
            ));
        }).onFailure(error -> {
            System.out.println("QuorumBasedReplica: Internal GET failed - key: " + getRequest.key() + 
                              ", error: " + error.getMessage() + ", correlationId: " + getRequest.correlationId());
            
            InternalGetResponse response = new InternalGetResponse(getRequest.key(), null, getRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_GET_RESPONSE,
                serializePayload(response), getRequest.correlationId()
            ));
        });
    }
    
    private void handleInternalSetRequest(Message message) {
        InternalSetRequest setRequest = deserializePayload(message.payload(), InternalSetRequest.class);
        VersionedValue value = new VersionedValue(setRequest.value(), setRequest.timestamp());
        
        System.out.println("QuorumBasedReplica: Processing internal SET request - key: " + setRequest.key() + 
                          ", value: " + new String(setRequest.value()) + ", timestamp: " + setRequest.timestamp() + 
                          ", correlationId: " + setRequest.correlationId() + ", from: " + message.source());
        
        // Perform local storage operation
        ListenableFuture<Boolean> future = storage.set(setRequest.key().getBytes(), value);
        
        future.onSuccess(success -> {
            System.out.println("QuorumBasedReplica: Internal SET completed - key: " + setRequest.key() + 
                              ", success: " + success + ", correlationId: " + setRequest.correlationId());
            
            InternalSetResponse response = new InternalSetResponse(setRequest.key(), success, setRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_SET_RESPONSE,
                serializePayload(response), setRequest.correlationId()
            ));
        }).onFailure(error -> {
            System.out.println("QuorumBasedReplica: Internal SET failed - key: " + setRequest.key() + 
                              ", error: " + error.getMessage() + ", correlationId: " + setRequest.correlationId());
            
            InternalSetResponse response = new InternalSetResponse(setRequest.key(), false, setRequest.correlationId());
            messageBus.sendMessage(new Message(
                networkAddress, message.source(), MessageType.INTERNAL_SET_RESPONSE,
                serializePayload(response), setRequest.correlationId()
            ));
        });
    }
    
    private void handleInternalGetResponse(Message message) {
        InternalGetResponse response = deserializePayload(message.payload(), InternalGetResponse.class);
        
        System.out.println("QuorumBasedReplica: Processing internal GET response - key: " + response.key() + 
                          ", internalCorrelationId: " + response.correlationId() + ", from: " + message.source());
        
        // Find the pending request by matching the internal correlation ID
        QuorumRequest quorumRequest = null;
        String clientCorrelationId = null;
        
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest pending = entry.getValue();
            if (pending instanceof QuorumRequest qr && 
                qr.operation == QuorumRequest.Operation.GET &&
                qr.hasInternalCorrelationId(response.correlationId())) {
                // Found the correct request that matches this internal correlation ID
                quorumRequest = qr;
                clientCorrelationId = entry.getKey();
                System.out.println("QuorumBasedReplica: Found matching GET request - clientCorrelationId: " + 
                                  clientCorrelationId + ", key: " + quorumRequest.key);
                break;
            }
        }
        
        if (quorumRequest != null) {
            quorumRequest.addResponse(message.source(), response.value());
            
            if (quorumRequest.hasQuorum(calculateQuorumSize())) {
                // Send response to client with latest value
                VersionedValue latestValue = quorumRequest.getLatestValue();
                GetResponse clientResponse = new GetResponse(quorumRequest.key, latestValue);
                
                Message clientMessage = new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse), clientCorrelationId
                );

                System.out.println("QuorumBasedReplica: Sending client GET response - key: " + quorumRequest.key + 
                                  ", value: " + (latestValue != null ? new String(latestValue.value()) : "null") + 
                                  ", clientCorrelationId: " + clientCorrelationId);
                
                messageBus.reply(quorumRequest.responseContext, clientMessage);
                pendingRequests.remove(clientCorrelationId);
            }
        } else {
            System.out.println("QuorumBasedReplica: No matching GET request found for internalCorrelationId: " + 
                              response.correlationId());
        }
    }
    
    private void handleInternalSetResponse(Message message) {
        InternalSetResponse response = deserializePayload(message.payload(), InternalSetResponse.class);
        
        System.out.println("QuorumBasedReplica: Processing internal SET response - key: " + response.key() + 
                          ", success: " + response.success() + ", internalCorrelationId: " + response.correlationId() + 
                          ", from: " + message.source());
        
        // Find the pending request by matching the internal correlation ID
        QuorumRequest quorumRequest = null;
        String clientCorrelationId = null;
        
        for (Map.Entry<String, PendingRequest> entry : pendingRequests.entrySet()) {
            PendingRequest pending = entry.getValue();
            if (pending instanceof QuorumRequest qr && 
                qr.operation == QuorumRequest.Operation.SET &&
                qr.hasInternalCorrelationId(response.correlationId())) {
                // Found the correct request that matches this internal correlation ID
                quorumRequest = qr;
                clientCorrelationId = entry.getKey();
                System.out.println("QuorumBasedReplica: Found matching SET request - clientCorrelationId: " + 
                                  clientCorrelationId + ", key: " + quorumRequest.key);
                break;
            }
        }
        
        if (quorumRequest != null) {
            quorumRequest.addResponse(message.source(), response.success());
            
            if (quorumRequest.hasQuorum(calculateQuorumSize())) {
                // Send response to client
                boolean success = quorumRequest.getSuccessCount() >= calculateQuorumSize();
                SetResponse clientResponse = new SetResponse(quorumRequest.key, success);
                
                Message clientMessage = new Message(
                    networkAddress, quorumRequest.clientAddress, MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse), clientCorrelationId
                );
                
                System.out.println("QuorumBasedReplica: Sending client SET response - key: " + quorumRequest.key + 
                                  ", success: " + success + ", clientCorrelationId: " + clientCorrelationId);
                
                if (quorumRequest.responseContext != null) {
                    messageBus.reply(quorumRequest.responseContext, clientMessage);
                } else {
                    messageBus.sendMessage(clientMessage);
                }
                
                pendingRequests.remove(clientCorrelationId);
            }
        } else {
            System.out.println("QuorumBasedReplica: No matching SET request found for internalCorrelationId: " + 
                              response.correlationId());
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
        MessageContext responseContext;
        
        private final Map<NetworkAddress, Object> responses = new HashMap<>();
        private final Set<String> internalCorrelationIds = new HashSet<>();
        
        QuorumRequest(String requestId, NetworkAddress clientAddress, Operation operation,
                     String key, VersionedValue setValue, long timestamp, Timeout timeout) {
            super(requestId, clientAddress, key, timeout);
            this.operation = operation;
            this.setValue = setValue;
            this.timestamp = timestamp;
        }
        
        void addResponse(NetworkAddress source, Object response) {
            responses.put(source, response);
        }
        
        void addInternalCorrelationId(String internalCorrelationId) {
            internalCorrelationIds.add(internalCorrelationId);
        }
        
        boolean hasInternalCorrelationId(String internalCorrelationId) {
            return internalCorrelationIds.contains(internalCorrelationId);
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