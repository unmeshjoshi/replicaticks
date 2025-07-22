package replicated.algorithms.quorum;

import replicated.future.ListenableFuture;
import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.network.id.ReplicaId;
import replicated.replica.Replica;
import replicated.storage.Storage;
import replicated.storage.VersionedValue;

import java.util.List;
import java.util.Map;

/**
 * Quorum-based replica implementation for distributed key-value store.
 * This implementation uses majority quorum consensus for read and write operations.
 * 
 * This is an example implementation of the replication framework, demonstrating
 * how to build a specific consensus algorithm using the framework's abstractions.
 */
public final class QuorumReplica extends Replica {

    /**
     * Creates a QuorumBasedReplica with the specified configuration.
     */
    public QuorumReplica(ReplicaId replicaId, NetworkAddress networkAddress, List<NetworkAddress> peers,
                         MessageBus messageBus, MessageCodec messageCodec, Storage storage, int requestTimeoutTicks) {
        super(replicaId, networkAddress, peers, messageBus, messageCodec, storage, requestTimeoutTicks);
    }

    /**
     * Creates a QuorumBasedReplica with default timeout.
     */
    public QuorumReplica(ReplicaId replicaId, NetworkAddress networkAddress, List<NetworkAddress> peers,
                         MessageBus messageBus, MessageCodec messageCodec, Storage storage) {
        this(replicaId, networkAddress, peers, messageBus, messageCodec, storage, 1000); // Default 1000 ticks timeout
    }

    @Override
    public void onMessageReceived(Message message, MessageContext ctx) {
        if (messageBus == null || storage == null) {
            // Skip message processing if not fully configured
            return;
        }

        MessageType mt = message.messageType();
        if (mt == MessageType.CLIENT_GET_REQUEST) {
            handleClientGetRequest(message, ctx);
        } else if (mt == MessageType.CLIENT_SET_REQUEST) {
            handleClientSetRequest(message, ctx);
        } else if (mt == MessageType.INTERNAL_GET_REQUEST) {
            handleInternalGetRequest(message);
        } else if (mt == MessageType.INTERNAL_SET_REQUEST) {
            handleInternalSetRequest(message);
        } else if (mt == MessageType.INTERNAL_GET_RESPONSE) {
            handleInternalGetResponse(message);
        } else if (mt == MessageType.INTERNAL_SET_RESPONSE) {
            handleInternalSetResponse(message);
        } // else ignore unknown
    }


    // Quorum-specific message handlers

    private void handleClientGetRequest(Message message, MessageContext ctx) {
        String correlationId = message.correlationId();
        GetRequest clientRequest = deserializePayload(message.payload(), GetRequest.class);
        NetworkAddress clientAddress = message.source();

        logIncomingGetRequest(clientRequest, correlationId, clientAddress);

        var quorumCallback = createGetQuorumCallback();
        quorumCallback.onSuccess(responses -> sendSuccessGetResponse(clientRequest, correlationId, clientAddress, ctx, responses))
                     .onFailure(error -> sendFailureGetResponse(clientRequest, correlationId, clientAddress, ctx, error));

        broadcastToAllReplicas(quorumCallback, (node, correlationId1) -> {
            InternalGetRequest internalRequest = new InternalGetRequest(clientRequest.key(), correlationId1);
            return Message.networkMessage(
                    networkAddress, node, MessageType.INTERNAL_GET_REQUEST,
                    serializePayload(internalRequest), correlationId1
            );
        });
    }

    private AsyncQuorumCallback<InternalGetResponse> createGetQuorumCallback() {
        List<NetworkAddress> allNodes = getAllNodes();
        return new AsyncQuorumCallback<>(
                allNodes.size(),
                response -> response != null && response.value() != null
        );
    }

    // Logging helpers
    private void logIncomingGetRequest(GetRequest req, String correlationId, NetworkAddress clientAddr) {
        System.out.println("QuorumBasedReplica: Processing client GET request - key: " + req.key() +
                ", correlationId: " + correlationId + ", from: " + clientAddr);
    }

    private void sendSuccessGetResponse(GetRequest req, String correlationId, NetworkAddress clientAddr,
                                        MessageContext ctx, Map<NetworkAddress, InternalGetResponse> responses) {
        VersionedValue latestValue = getLatestValueFromResponses(responses);
        GetResponse clientResponse = new GetResponse(req.key(), latestValue);
        Message clientMessage = Message.networkMessage(
                networkAddress, null, MessageType.CLIENT_GET_RESPONSE,
                serializePayload(clientResponse), correlationId
        );

        logSuccessfulGetResponse(req, correlationId, latestValue);
        messageBus.reply(ctx, clientMessage);
    }

    private void sendFailureGetResponse(GetRequest req, String correlationId, NetworkAddress clientAddr,
                                        MessageContext ctx, Throwable error) {
        GetResponse clientResponse = new GetResponse(req.key(), null);
        Message clientMessage = Message.networkMessage(
                networkAddress, null, MessageType.CLIENT_GET_RESPONSE,
                serializePayload(clientResponse), correlationId
        );

        logFailedGetResponse(req, correlationId, error);
        messageBus.reply(ctx, clientMessage);
    }

    private void logSuccessfulGetResponse(GetRequest req, String correlationId, VersionedValue latestValue) {
        String valueDescription = latestValue != null ? new String(latestValue.value()) : "null";
        System.out.println("QuorumBasedReplica: Sending client GET response - key: " + req.key() +
                ", value: " + valueDescription + ", correlationId: " + correlationId);
    }

    private void logFailedGetResponse(GetRequest req, String correlationId, Throwable error) {
        System.out.println("QuorumBasedReplica: Sending client GET error response - key: " + req.key() +
                ", error: " + error.getMessage() + ", correlationId: " + correlationId);
    }

    private void handleClientSetRequest(Message message, MessageContext ctx) {
        String correlationId = message.correlationId();
        SetRequest clientRequest = deserializePayload(message.payload(), SetRequest.class);
        NetworkAddress clientAddress = message.source();

        logIncomingSetRequest(clientRequest, correlationId, clientAddress);

        AsyncQuorumCallback<InternalSetResponse> quorumCallback = createSetQuorumCallback();

        quorumCallback.onSuccess(responses -> sendSuccessSetResponseToClient(clientRequest, correlationId, clientAddress, ctx))
                     .onFailure(error -> sendFailureSetResponseToClient(clientRequest, correlationId, clientAddress, ctx, error));

       broadcastToAllReplicas(quorumCallback, (node, correlationId1) -> {
           InternalSetRequest internalRequest = new InternalSetRequest(
                   clientRequest.key(), clientRequest.value(), 0, correlationId1
           );

           return Message.networkMessage(
                   networkAddress, node, MessageType.INTERNAL_SET_REQUEST,
                   serializePayload(internalRequest), correlationId1
           );
       });
    }

    // SET helper methods

    private void logIncomingSetRequest(SetRequest req, String correlationId, NetworkAddress clientAddr) {
        System.out.println("QuorumBasedReplica: Processing client SET request - key: " + req.key() +
                ", value: " + new String(req.value()) + ", correlationId: " + correlationId +
                ", from: " + clientAddr);
    }

    private AsyncQuorumCallback<InternalSetResponse> createSetQuorumCallback() {
        List<NetworkAddress> allNodes = getAllNodes();
        return new AsyncQuorumCallback<>(
                allNodes.size(),
                response -> response != null && response.success()
        );
    }

    private void sendSuccessSetResponseToClient(SetRequest req, String correlationId, NetworkAddress clientAddr,
                                                MessageContext ctx) {
        SetResponse clientResponse = new SetResponse(req.key(), true);
        Message clientMessage = Message.networkMessage(
                networkAddress, null, MessageType.CLIENT_SET_RESPONSE,
                serializePayload(clientResponse), correlationId
        );

        logSuccessfulSetResponse(req, correlationId);
        messageBus.reply(ctx, clientMessage);
    }

    private void sendFailureSetResponseToClient(SetRequest req, String correlationId, NetworkAddress clientAddr,
                                                MessageContext ctx, Throwable error) {
        SetResponse clientResponse = new SetResponse(req.key(), false);
        Message clientMessage = Message.networkMessage(
                networkAddress, null, MessageType.CLIENT_SET_RESPONSE,
                serializePayload(clientResponse), correlationId
        );

        logFailedSetResponse(req, correlationId, error);
        messageBus.reply(ctx, clientMessage);
    }

    private void logSuccessfulSetResponse(SetRequest req, String correlationId) {
        System.out.println("QuorumBasedReplica: Sending client SET success response - key: " + req.key() +
                ", correlationId: " + correlationId);
    }

    private void logFailedSetResponse(SetRequest req, String correlationId, Throwable error) {
        System.out.println("QuorumBasedReplica: Sending client SET failure response - key: " + req.key() +
                ", error: " + error.getMessage() + ", correlationId: " + correlationId);
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
            messageBus.sendMessage(Message.networkMessage(
                    networkAddress, message.source(), MessageType.INTERNAL_GET_RESPONSE,
                    serializePayload(response), getRequest.correlationId()
            ));
        }).onFailure(error -> {
            System.out.println("QuorumBasedReplica: Internal GET failed - key: " + getRequest.key() +
                    ", error: " + error.getMessage() + ", correlationId: " + getRequest.correlationId());

            InternalGetResponse response = new InternalGetResponse(getRequest.key(), null, getRequest.correlationId());
            messageBus.sendMessage(Message.networkMessage(
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
            messageBus.sendMessage(Message.networkMessage(
                    networkAddress, message.source(), MessageType.INTERNAL_SET_RESPONSE,
                    serializePayload(response), setRequest.correlationId()
            ));
        }).onFailure(error -> {
            System.out.println("QuorumBasedReplica: Internal SET failed - key: " + setRequest.key() +
                    ", error: " + error.getMessage() + ", correlationId: " + setRequest.correlationId());

            InternalSetResponse response = new InternalSetResponse(setRequest.key(), false, setRequest.correlationId());
            messageBus.sendMessage(Message.networkMessage(
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