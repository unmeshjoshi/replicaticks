package replicated.replica;

import replicated.future.ListenableFuture;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.storage.Storage;
import replicated.storage.VersionedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        this(name, networkAddress, peers, messageBus, storage, 100); // Default 100 ticks timeout
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

    }

    /**
     * Generates a unique correlation ID for internal messages.
     */
    private String generateCorrelationId() {
        return "internal-" + UUID.randomUUID();
        //internal correlation ID should be UUID as it should not use System.currentTimeMillis
        //Multiple internal messages can be sent at the same millisecond.
    }

    // Quorum-specific message handlers

    private void handleClientGetRequest(Message message, MessageContext ctx) {
        String correlationId = message.correlationId();
        GetRequest clientRequest = deserializePayload(message.payload(), GetRequest.class);

        System.out.println("QuorumBasedReplica: Processing client GET request - key: " + clientRequest.key() +
                ", correlationId: " + correlationId + ", from: " + message.source());

        // Create AsyncQuorumCallback for this request
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);

        AsyncQuorumCallback<InternalGetResponse> quorumCallback = new AsyncQuorumCallback<>(
                allNodes.size(),
                response -> {
                    // Success condition: check if this individual response is successful
                    return response != null && response.value() != null;
                }
        );
        // Set up future completion handlers
        ListenableFuture<Map<NetworkAddress, InternalGetResponse>> quorumFuture = quorumCallback.getQuorumFuture();

        quorumFuture.onSuccess(responses -> {
            // Quorum achieved - find the latest value and send response to client
            VersionedValue latestValue = getLatestValueFromResponses(responses);
            GetResponse clientResponse = new GetResponse(clientRequest.key(), latestValue);

            Message clientMessage = new Message(
                    networkAddress, message.source(), MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse), correlationId
            );

            System.out.println("QuorumBasedReplica: Sending client GET response - key: " + clientRequest.key() +
                    ", value: " + (latestValue != null ? new String(latestValue.value()) : "null") +
                    ", correlationId: " + correlationId);

            messageBus.reply(ctx, clientMessage);

        }).onFailure(error -> {
            // Quorum failed - send error response to client
            GetResponse clientResponse = new GetResponse(clientRequest.key(), null);

            Message clientMessage = new Message(
                    networkAddress, message.source(), MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse), correlationId
            );

            System.out.println("QuorumBasedReplica: Sending client GET error response - key: " + clientRequest.key() +
                    ", error: " + error.getMessage() + ", correlationId: " + correlationId);

            messageBus.reply(ctx, clientMessage);
        });

        // Send INTERNAL_GET_REQUEST to all peers (including self)
        for (NetworkAddress node : allNodes) {
            String internalCorrelationId = generateCorrelationId();
            waitingList.add(internalCorrelationId, quorumCallback);

            InternalGetRequest internalRequest = new InternalGetRequest(clientRequest.key(), internalCorrelationId);
            messageBus.sendMessage(new Message(
                    networkAddress, node, MessageType.INTERNAL_GET_REQUEST,
                    serializePayload(internalRequest), internalCorrelationId
            ));
        }
    }

    /**
     * Extracts the latest value from quorum responses.
     */
    private VersionedValue getLatestValueFromResponses(Map<NetworkAddress, InternalGetResponse> responses) {
        VersionedValue latestValue = null;
        long latestTimestamp = -1;

        for (InternalGetResponse response : responses.values()) {
            if (response != null && response.value() != null) {
                VersionedValue value = response.value();
                if (value.timestamp() > latestTimestamp) {
                    latestValue = value;
                    latestTimestamp = value.timestamp();
                }
            }
        }

        return latestValue;
    }

    private void handleClientSetRequest(Message message, MessageContext ctx) {
        String correlationId = message.correlationId();
        SetRequest clientRequest = deserializePayload(message.payload(), SetRequest.class);

        System.out.println("QuorumBasedReplica: Processing client SET request - key: " + clientRequest.key() +
                ", value: " + new String(clientRequest.value()) + ", correlationId: " + correlationId +
                ", from: " + message.source());

        // Send INTERNAL_SET_REQUEST to all peers (including self)
        List<NetworkAddress> allNodes = new ArrayList<>(peers);
        allNodes.add(networkAddress);

        AsyncQuorumCallback<InternalSetResponse> quorumCallback = new AsyncQuorumCallback<>(
                allNodes.size(),  // Fixed: use allNodes.size() instead of peers.size()
                response -> {
                    // Success condition: check if this individual response is successful
                    return response != null && response.success();
                }
        );

        // Set up future completion handlers
        ListenableFuture<Map<NetworkAddress, InternalSetResponse>> quorumFuture = quorumCallback.getQuorumFuture();

        quorumFuture.onSuccess(responses -> {
            // Quorum achieved - find the latest value and send response to client
            SetResponse clientResponse = new SetResponse(clientRequest.key(), true);
            Message clientMessage = new Message(
                    networkAddress, message.source(), MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse), correlationId
            );

            messageBus.reply(ctx, clientMessage);

        }).onFailure(error -> {
            // Quorum failed - send error response to client
            SetResponse clientResponse = new SetResponse(clientRequest.key(), false);
            Message clientMessage = new Message(
                    networkAddress, message.source(), MessageType.CLIENT_RESPONSE,
                    serializePayload(clientResponse), correlationId
            );
            messageBus.reply(ctx, clientMessage);
        });

        for (NetworkAddress node : allNodes) {
            String internalCorrelationId = generateCorrelationId();
            // Track the internal correlation ID for this request
            InternalSetRequest internalRequest = new InternalSetRequest(
                    clientRequest.key(), clientRequest.value(), 0, internalCorrelationId
            );

            waitingList.add(internalCorrelationId, quorumCallback);

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

        // Route the response to the RequestWaitingList
        waitingList.handleResponse(message.correlationId(), response, message.source());
    }

    private void handleInternalSetResponse(Message message) {
        InternalSetResponse response = deserializePayload(message.payload(), InternalSetResponse.class);

        System.out.println("QuorumBasedReplica: Processing internal SET response - key: " + response.key() +
                ", success: " + response.success() + ", internalCorrelationId: " + response.correlationId() +
                ", from: " + message.source());
        waitingList.handleResponse(message.correlationId(), response, message.source());
    }
}