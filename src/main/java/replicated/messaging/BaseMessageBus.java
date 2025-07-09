package replicated.messaging;

import replicated.network.MessageContext;
import replicated.network.Network;

import java.util.List;

/**
 * Base abstract class for message bus implementations.
 * Contains common functionality shared between client and server message buses.
 */
public abstract class BaseMessageBus {
    
    protected final Network network;
    protected final MessageCodec messageCodec;
    
    /**
     * Creates a BaseMessageBus with the given network and codec dependencies.
     * 
     * @param network the underlying network for message transmission
     * @param messageCodec the codec for message encoding/decoding
     * @throws IllegalArgumentException if either parameter is null
     */
    protected BaseMessageBus(Network network, MessageCodec messageCodec) {
        if (network == null) {
            throw new IllegalArgumentException("Network cannot be null");
        }
        if (messageCodec == null) {
            throw new IllegalArgumentException("MessageCodec cannot be null");
        }
        
        this.network = network;
        this.messageCodec = messageCodec;
    }
    
    /**
     * Sends a message through the underlying network.
     * 
     * @param message the message to send
     * @throws IllegalArgumentException if message is null
     */
    public void sendMessage(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        
        network.send(message);
    }
    
    /**
     * Establishes a connection to the destination and returns the actual local address
     * assigned by the network layer. This delegates to the network implementation.
     * 
     * Implementation behavior:
     * - SimulatedNetwork: Returns localhost with simulated ephemeral port
     * - NioNetwork: Establishes actual socket connection and returns OS-assigned local address
     * 
     * @param destination the destination address to connect to
     * @return the actual local address assigned for this connection
     */
    public NetworkAddress establishConnection(NetworkAddress destination) {
        // Delegate to the network layer to establish the actual connection
        // This allows different network implementations to handle connection establishment
        // appropriately (simulated vs. real socket connections)
        return network.establishConnection(destination);
    }
    
    /**
     * Routes received messages to registered handlers.
     * This method implements the reactive Service Layer tick() pattern.
     * 
     * Note: This method does NOT tick the network - that is handled by the centralized
     * SimulationDriver to maintain proper tick orchestration. This method only processes
     * messages that were delivered in previous ticks.
     */
    public void tick() {
        // Route messages to registered handlers (implemented by subclasses)
        // The network is ticked separately by SimulationDriver to maintain
        // centralized tick orchestration and deterministic ordering
        routeMessagesToHandlers();
    }
    
    /**
     * Routes messages to their respective handlers.
     * This is implemented by subclasses based on their specific routing logic.
     */
    protected abstract void routeMessagesToHandlers();
    
    /**
     * Broadcasts a message from the source to all recipients in the list.
     * The sender (source) will not receive the message - only the other recipients.
     * 
     * @param source the source address sending the broadcast
     * @param recipients the list of addresses to send the message to
     * @param messageType the type of message to broadcast
     * @param payload the message payload
     */
    public void broadcast(NetworkAddress source, List<NetworkAddress> recipients, 
                         MessageType messageType, byte[] payload) {
        for (NetworkAddress recipient : recipients) {
            if (!recipient.equals(source)) {  // Don't send to self
                String correlationId = generateCorrelationId();
                Message message = new Message(source, recipient, messageType, payload, correlationId);
                sendMessage(message);
            }
        }
    }
    
    /**
     * Generates a unique correlation ID for message tracking.
     */
    protected String generateCorrelationId() {
        return "msg-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }
    
    /**
     * Helper method for direct-channel replies.
     */
    public void reply(MessageContext ctx, Message response) {
        if (response == null) {
            throw new IllegalArgumentException("Response cannot be null");
        }
        if (ctx != null && ctx.canRouteResponse()) {
            network.sendOnChannel(ctx.getSourceChannel(), response);
        } else {
            // Fallback to normal send if direct channel not available (e.g., simulation or ctx null)
            network.send(response);
        }
    }
    
    // === CLIENT-SPECIFIC METHODS (implemented by ClientMessageBus) ===
    
    /**
     * Registers a client handler and assigns it a network address.
     * This is a client-specific method implemented by ClientMessageBus.
     */
    public NetworkAddress registerClient(MessageHandler clientHandler) {
        throw new UnsupportedOperationException("registerClient is only supported by ClientMessageBus");
    }
    
    /**
     * Registers a message handler for the given correlation ID.
     * This is a client-specific method implemented by ClientMessageBus.
     */
    public void registerCorrelationIdHandler(String correlationId, MessageHandler handler) {
        throw new UnsupportedOperationException("registerCorrelationIdHandler is only supported by ClientMessageBus");
    }
    
    /**
     * Unregisters a message handler for the given correlation ID.
     * This is a client-specific method implemented by ClientMessageBus.
     */
    public void unregisterCorrelationIdHandler(String correlationId) {
        throw new UnsupportedOperationException("unregisterCorrelationIdHandler is only supported by ClientMessageBus");
    }
} 