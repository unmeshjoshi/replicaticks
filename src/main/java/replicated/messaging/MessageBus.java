package replicated.messaging;

import replicated.network.MessageCallback;
import replicated.network.MessageContext;
import replicated.network.Network;

import java.util.*;

/**
 * Unified MessageBus that handles both correlation ID and address-based routing.
 * This replaces the separate ClientMessageBus and ServerMessageBus classes.
 * 
 * Routing Priority:
 * 1. Correlation ID-based routing (for client responses)
 * 2. Address-based routing (for server requests)
 * 
 * If a message has both correlation ID and destination address:
 * - Correlation ID takes priority (client response pattern)
 * - Address routing is used as fallback if no correlation handler exists
 */
public class MessageBus implements MessageCallback {
    
    protected final Network network;
    protected final MessageCodec messageCodec;
    
    // Dual routing maps
    private final Map<String, MessageHandler> correlationIdHandlers;
    private final Map<NetworkAddress, MessageHandler> addressHandlers;
    
    /**
     * Creates a MessageBus with the given network and codec dependencies.
     * 
     * @param network the underlying network for message transmission
     * @param messageCodec the codec for message encoding/decoding
     * @throws IllegalArgumentException if either parameter is null
     */
    public MessageBus(Network network, MessageCodec messageCodec) {
        if (network == null) {
            throw new IllegalArgumentException("Network cannot be null");
        }
        if (messageCodec == null) {
            throw new IllegalArgumentException("MessageCodec cannot be null");
        }
        
        this.network = network;
        this.messageCodec = messageCodec;
        this.correlationIdHandlers = new HashMap<>();
        this.addressHandlers = new HashMap<>();
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

    // === CORRELATION ID ROUTING (Client Response Pattern) ===
    
    /**
     * Registers a message handler for the given correlation ID.
     * When messages with this correlation ID are received, they will be routed to the handler.
     * This is the client pattern - for components that send requests and expect responses.
     * 
     * @param correlationId the correlation ID to register the handler for
     * @param handler the message handler to receive messages
     */
    public void registerHandler(String correlationId, MessageHandler handler) {
        if (correlationId == null) {
            throw new IllegalArgumentException("Correlation ID cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        correlationIdHandlers.put(correlationId, handler);
    }
    
    /**
     * Unregisters the message handler for the given correlation ID.
     * Messages with this correlation ID will no longer be routed to any handler.
     * 
     * @param correlationId the correlation ID to unregister
     */
    public void unregisterHandler(String correlationId) {
        if (correlationId == null) {
            throw new IllegalArgumentException("Correlation ID cannot be null");
        }
        correlationIdHandlers.remove(correlationId);
    }
    
    // === ADDRESS ROUTING (Server Request Pattern) ===
    
    /**
     * Registers a message handler for the given network address.
     * When messages are received for this address, they will be routed to the handler.
     * This is the server pattern - for components that listen on specific addresses.
     * 
     * @param address the network address to register the handler for
     * @param handler the message handler to receive messages
     */
    public void registerHandler(NetworkAddress address, MessageHandler handler) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        addressHandlers.put(address, handler);
    }
    
    /**
     * Unregisters the message handler for the given network address.
     * Messages sent to this address will no longer be routed to any handler.
     * 
     * @param address the network address to unregister
     */
    public void unregisterHandler(NetworkAddress address) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        addressHandlers.remove(address);
    }
    
    // === UNIFIED ROUTING LOGIC ===
    
    /**
     * Callback method that receives messages from the network.
     * This is called by the network when messages are available.
     * 
     * Routing Priority:
     * 1. Check correlation ID first (client response pattern)
     * 2. Fall back to address routing (server request pattern)
     * 3. Log unroutable messages (no crash)
     */
    @Override
    public void onMessage(Message message, MessageContext context) {
        String correlationId = message.correlationId();
        NetworkAddress destination = message.destination();
        
        System.out.println("MessageBus: Routing message " + message.messageType() + " from " + message.source() + 
                          " to " + destination + " (correlationId=" + correlationId + ")");
        
        // PRIORITY 1: Correlation ID routing (client responses)
        //If the message is sent in response to an earlier message sent with a specific correlation ID,
        //then it should be routed to the corresponding handler for that correlation ID
        if (isResponse(message, correlationId)) {
            MessageHandler correlationHandler = correlationIdHandlers.get(correlationId);
            if (correlationHandler != null) {
                System.out.println("MessageBus: Delivering to correlation ID handler (" + correlationId + ")");
                correlationHandler.onMessageReceived(message, context);
                // Remove the handler after use (one-time correlation ID handlers)
                correlationIdHandlers.remove(correlationId);
                return;
            }
        }
        
        // PRIORITY 2: Address routing (server requests)
        if (isMessageFor(destination)) {
            MessageHandler addressHandler = addressHandlers.get(destination);
            if (addressHandler != null) {
                System.out.println("MessageBus: Delivering to address handler (" + destination + ")");
                addressHandler.onMessageReceived(message, context);
                return;
            }
        }
        
        // PRIORITY 3: Unroutable message (log but don't crash)
        System.out.println("MessageBus: No handler found for message " + message.messageType() + 
                          " (correlationId=" + correlationId + ", destination=" + destination + ")");
    }

    private static boolean isMessageFor(NetworkAddress destination) {
        return destination != null;
    }

    private static boolean isResponse(Message message, String correlationId) {
        return correlationId != null && message.messageType().isResponse();
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
        // Route messages to registered handlers (now handled by onMessage callback)
        // The network is ticked separately by SimulationDriver to maintain
        // centralized tick orchestration and deterministic ordering
        routeMessagesToHandlers();
    }
    
    /**
     * Routes messages to their respective handlers based on correlation ID or destination address.
     * This method is now called by the network callback instead of polling.
     */
    protected void routeMessagesToHandlers() {
        // This method is now handled by the onMessage callback
        // The network will call onMessage when messages are available
    }
    
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
    
    private static final java.util.concurrent.atomic.AtomicLong correlationIdCounter = new java.util.concurrent.atomic.AtomicLong(0);
    
    /**
     * Generates a unique correlation ID for message tracking.
     */
    protected String generateCorrelationId() {
        return "msg-" + System.currentTimeMillis() + "-" + correlationIdCounter.incrementAndGet();
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
    
    // === LEGACY CLIENT-SPECIFIC METHODS (for backward compatibility) ===
    

    
    /**
     * Registers a message handler for the given correlation ID.
     * This is now handled by the unified registerHandler method.
     */
    public void registerCorrelationIdHandler(String correlationId, MessageHandler handler) {
        registerHandler(correlationId, handler);
    }
    
    /**
     * Unregisters a message handler for the given correlation ID.
     * This is now handled by the unified unregisterHandler method.
     */
    public void unregisterCorrelationIdHandler(String correlationId) {
        unregisterHandler(correlationId);
    }
} 