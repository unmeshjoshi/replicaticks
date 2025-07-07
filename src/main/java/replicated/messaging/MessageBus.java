package replicated.messaging;

import replicated.network.Network;
import replicated.network.MessageContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * MessageBus provides a higher-level messaging abstraction over the Network layer.
 * It handles component registration, message routing, and coordinates tick() calls.
 * 
 * Supports two routing patterns:
 * 1. Server pattern: Register handler by address (IP:port) - for servers that listen on specific addresses
 * 2. Client pattern: Register handler by correlation ID - for clients that send requests and expect responses
 */
public class MessageBus {
    
    private final Network network;
    private final MessageCodec messageCodec;
    private final Map<NetworkAddress, MessageHandler> registeredHandlers;
    private final Map<String, MessageHandler> correlationIdHandlers = new HashMap<>();
    private final List<MessageHandler> clientHandlers = new ArrayList<>();
    private final Map<Object, NetworkAddress> clientAddresses;
    private final AtomicInteger clientPortCounter;
    
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
        this.registeredHandlers = new HashMap<>();
        this.clientAddresses = new HashMap<>();
        this.clientPortCounter = new AtomicInteger(9000); // Start client ports at 9000
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
     * Sends a message from a client, automatically assigning a source address.
     * The client's address is automatically determined and managed by the MessageBus.
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
     * Generates a unique correlation ID for message tracking.
     */
    private String generateCorrelationId() {
        return "msg-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }
    
    /**
     * Registers a client handler and assigns it a network address.
     * This ensures the client can receive responses even before sending its first message.
     * 
     * @param clientHandler the client handler to register
     * @return the assigned network address for the client
     */
    public NetworkAddress registerClient(MessageHandler clientHandler) {
        return getOrAssignClientAddress(clientHandler);
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
     * When messages are received for this address, they will be routed to the handler.
     * This is the server pattern - for components that listen on specific addresses.
     * 
     * @param address the network address to register the handler for
     * @param handler the message handler to receive messages
     */
    public void registerHandler(NetworkAddress address, MessageHandler handler) {
        registeredHandlers.put(address, handler);
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
        correlationIdHandlers.put(correlationId, handler);
    }
    
    /**
     * Registers a client handler to receive all messages (for client-side correlation ID routing).
     */
    public void registerClientHandler(MessageHandler handler) {
        if (handler == null) throw new NullPointerException();
        clientHandlers.add(handler);
    }
    
    /**
     * Unregisters the message handler for the given network address.
     * Messages sent to this address will no longer be routed to any handler.
     * 
     * @param address the network address to unregister
     */
    public void unregisterHandler(NetworkAddress address) {
        registeredHandlers.remove(address);
    }
    
    /**
     * Unregisters the message handler for the given correlation ID.
     * Messages with this correlation ID will no longer be routed to any handler.
     * 
     * @param correlationId the correlation ID to unregister
     */
    public void unregisterHandler(String correlationId) {
        correlationIdHandlers.remove(correlationId);
    }
    
    /**
     * Unregisters a client handler.
     */
    public void unregisterClientHandler(MessageHandler handler) {
        if (handler == null) throw new NullPointerException();
        clientHandlers.remove(handler);
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
     * Advances the simulation and routes received messages to registered handlers.
     * This method implements the reactive Service Layer tick() pattern by:
     * 1. Calling network.tick() to process pending network operations
     * 2. Retrieving received messages for all registered addresses
     * 3. Routing messages to their respective handlers
     */
    public void tick() {
        // First, advance the network simulation
        network.tick();
        
        // Then, route messages to registered handlers
        routeMessagesToHandlers();
    }
    
    private void routeMessagesToHandlers() {
        // Collect all messages from all registered addresses
        Set<NetworkAddress> allAddresses = new HashSet<>(registeredHandlers.keySet());
        // Optionally, add more addresses if needed (e.g., ephemeral client addresses)
        // But for now, we only care about registeredHandlers

        List<Message> allMessages = new ArrayList<>();
        for (NetworkAddress address : allAddresses) {
            List<Message> messages = network.receive(address);
            if (!messages.isEmpty()) {
                System.out.println("MessageBus: Received " + messages.size() + " messages from " + address);
                allMessages.addAll(messages);
            }
        }

        // Only print debug info if there are actual messages to process
        if (!allMessages.isEmpty()) {
            System.out.println("MessageBus: Processing " + allMessages.size() + " total messages");
        }

        for (Message message : allMessages) {
            MessageContext ctx = network.getContextFor(message);
            String correlationId = message.correlationId();
            
            System.out.println("MessageBus: Routing message " + message.messageType() + " from " + message.source() + 
                              " to " + message.destination() + " (correlationId=" + correlationId + ")");
            
            // Determine if this is a response message (from server to client)
            boolean isResponse = message.messageType() == MessageType.CLIENT_RESPONSE;
            
            if (isResponse && correlationId != null) {
                // Response message: route by correlationId to client
                MessageHandler correlationHandler = correlationIdHandlers.get(correlationId);
                if (correlationHandler != null) {
                    System.out.println("MessageBus: Delivering response to correlationId handler");
                    correlationHandler.onMessageReceived(message, ctx);
                } else {
                    System.out.println("MessageBus: No correlationId handler found for response, delivering to all client handlers");
                    for (MessageHandler clientHandler : clientHandlers) {
                        clientHandler.onMessageReceived(message, ctx);
                    }
                }
            } else {
                // Request message: route by address to server
                MessageHandler addressHandler = registeredHandlers.get(message.destination());
                if (addressHandler != null) {
                    System.out.println("MessageBus: Delivering request to address handler for " + message.destination());
                    addressHandler.onMessageReceived(message, ctx);
                } else {
                    System.out.println("MessageBus: No address handler found for request to " + message.destination());
                }
            }
        }
    }
    
    // === NEW helper for direct-channel replies ===
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
    
    /**
     * Registers a handler for a specific correlation ID (for client request/response correlation).
     */
    public void registerCorrelationIdHandler(String correlationId, MessageHandler handler) {
        if (correlationId == null || handler == null) throw new NullPointerException();
        correlationIdHandlers.put(correlationId, handler);
    }

    /**
     * Unregisters a handler for a specific correlation ID.
     */
    public void unregisterCorrelationIdHandler(String correlationId) {
        if (correlationId == null) throw new NullPointerException();
        correlationIdHandlers.remove(correlationId);
    }
} 