package replicated.messaging;

import replicated.network.MessageCallback;
import replicated.network.MessageContext;
import replicated.network.Network;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClientMessageBus provides message routing for client components.
 * Handles correlation ID-based routing where clients register handlers for specific correlation IDs.
 * 
 * This is used by client components that send requests and expect responses.
 */
public class ClientMessageBus extends MessageBus implements MessageCallback {
    
    private final Map<String, MessageHandler> correlationIdHandlers;
    private final List<MessageHandler> clientHandlers;
    private final Map<Object, NetworkAddress> clientAddresses;
    private final AtomicInteger clientPortCounter;
    
    /**
     * Creates a ClientMessageBus with the given network and codec dependencies.
     * 
     * @param network the underlying network for message transmission
     * @param messageCodec the codec for message encoding/decoding
     * @throws IllegalArgumentException if either parameter is null
     */
    public ClientMessageBus(Network network, MessageCodec messageCodec) {
        super(network, messageCodec);
        this.correlationIdHandlers = new HashMap<>();
        this.clientHandlers = new ArrayList<>();
        this.clientAddresses = new HashMap<>();
        this.clientPortCounter = new AtomicInteger(9000); // Start client ports at 9000
        
        // NOTE: Do not register directly with network - use MessageBusMultiplexer instead
    }
    
    /**
     * Sends a message from a client, automatically assigning a source address.
     * The client's address is automatically determined and managed by the ClientMessageBus.
     * 
     * @param destination the destination address for the message
     * @param messageType the type of message to send
     * @param payload the message payload
     * @param clientHandler the client handler (used to determine/assign client address)
     */
    public void sendClientMessage(NetworkAddress destination, MessageType messageType, 
                                 byte[] payload, MessageHandler clientHandler) {
        NetworkAddress clientAddress = getOrAssignClientAddress(clientHandler);
        String correlationId = generateCorrelationId();
        Message message = new Message(clientAddress, destination, messageType, payload, correlationId);
        sendMessage(message);
    }
    

    
    /**
     * Gets the existing client address or assigns a new one.
     * This simulates how real networks assign ephemeral ports to clients.
     */
    private NetworkAddress getOrAssignClientAddress(MessageHandler clientHandler) {
        return clientAddresses.computeIfAbsent(clientHandler, handler -> {
            NetworkAddress clientAddress = new NetworkAddress("127.0.0.1", clientPortCounter.getAndIncrement());
            // Also register the handler for this address to receive responses
            registerHandler(clientAddress, clientHandler);
            return clientAddress;
        });
    }
    
    /**
     * Registers a message handler for the given network address.
     * This is used internally to register client handlers for their assigned addresses.
     * 
     * @param address the network address to register the handler for
     * @param handler the message handler to receive messages
     */
    private void registerHandler(NetworkAddress address, MessageHandler handler) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        // Note: This is internal use only - clients don't register by address externally
    }
    
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
     * Registers a client handler to receive all messages (for client-side correlation ID routing).
     */
    public void registerClientHandler(MessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        clientHandlers.add(handler);
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
    
    /**
     * Unregisters a client handler.
     */
    public void unregisterClientHandler(MessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        clientHandlers.remove(handler);
    }
    
    // === OVERRIDE BASE CLASS CLIENT METHODS ===
    
    @Override
    public NetworkAddress registerClient(MessageHandler clientHandler) {
        return getOrAssignClientAddress(clientHandler);
    }
    
    @Override
    public void registerCorrelationIdHandler(String correlationId, MessageHandler handler) {
        registerHandler(correlationId, handler);
    }
    
    @Override
    public void unregisterCorrelationIdHandler(String correlationId) {
        unregisterHandler(correlationId);
    }
    
    /**
     * Callback method that receives messages from the network.
     * This is called by the network when messages are available.
     */
    @Override
    public void onMessage(Message message, MessageContext context) {
        // Only process messages destined for our client addresses
        if (clientAddresses.containsValue(message.destination())) {
            String correlationId = message.correlationId();
            
            System.out.println("ClientMessageBus: Routing message " + message.messageType() + " from " + message.source() + 
                              " to " + message.destination() + " (correlationId=" + correlationId + ")");
            
            // Route by correlation ID to client handler
            if (correlationId != null) {
                MessageHandler correlationHandler = correlationIdHandlers.get(correlationId);
                if (correlationHandler != null) {
                    System.out.println("ClientMessageBus: Delivering response to correlationId handler");
                    correlationHandler.onMessageReceived(message, context);
                    // Remove the handler after use (one-time correlation ID handlers)
                    correlationIdHandlers.remove(correlationId);
                } else {
                    System.out.println("ClientMessageBus: No correlationId handler found for response, delivering to all client handlers");
                    for (MessageHandler clientHandler : clientHandlers) {
                        clientHandler.onMessageReceived(message, context);
                    }
                }
            } else {
                System.out.println("ClientMessageBus: No correlation ID in message, delivering to all client handlers");
                for (MessageHandler clientHandler : clientHandlers) {
                    clientHandler.onMessageReceived(message, context);
                }
            }
        }
    }

    /**
     * Routes messages to their respective handlers based on correlation ID.
     * This method is now called by the network callback instead of polling.
     */
    @Override
    protected void routeMessagesToHandlers() {
        // This method is now handled by the onMessage callback
        // The network will call onMessage when messages are available
    }
} 