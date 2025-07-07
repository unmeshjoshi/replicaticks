package replicated.messaging;

import replicated.network.Network;
import replicated.network.MessageContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;

/**
 * ServerMessageBus provides message routing for server components.
 * Handles address-based routing where components register handlers for specific network addresses.
 * 
 * This is used by server components like Replicas that listen on specific addresses.
 */
public class ServerMessageBus extends BaseMessageBus {
    
    private final Map<NetworkAddress, MessageHandler> registeredHandlers;
    
    /**
     * Creates a ServerMessageBus with the given network and codec dependencies.
     * 
     * @param network the underlying network for message transmission
     * @param messageCodec the codec for message encoding/decoding
     * @throws IllegalArgumentException if either parameter is null
     */
    public ServerMessageBus(Network network, MessageCodec messageCodec) {
        super(network, messageCodec);
        this.registeredHandlers = new HashMap<>();
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
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        registeredHandlers.put(address, handler);
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
        registeredHandlers.remove(address);
    }
    
    /**
     * Routes messages to their respective handlers based on destination address.
     * This implements server-side routing where messages are delivered to handlers
     * registered for the destination address.
     */
    @Override
    protected void routeMessagesToHandlers() {
        // Collect all messages from all registered addresses
        Set<NetworkAddress> allAddresses = new HashSet<>(registeredHandlers.keySet());
        
        List<Message> allMessages = new ArrayList<>();
        for (NetworkAddress address : allAddresses) {
            List<Message> messages = network.receive(address);
            if (!messages.isEmpty()) {
                System.out.println("ServerMessageBus: Received " + messages.size() + " messages for " + address);
                allMessages.addAll(messages);
            }
        }

        // Only print debug info if there are actual messages to process
        if (!allMessages.isEmpty()) {
            System.out.println("ServerMessageBus: Processing " + allMessages.size() + " total messages");
        }

        for (Message message : allMessages) {
            MessageContext ctx = network.getContextFor(message);
            
            System.out.println("ServerMessageBus: Routing message " + message.messageType() + " from " + message.source() + 
                              " to " + message.destination() + " (correlationId=" + message.correlationId() + ")");
            
            // Route by destination address to server handler
            MessageHandler addressHandler = registeredHandlers.get(message.destination());
            if (addressHandler != null) {
                System.out.println("ServerMessageBus: Delivering message to address handler for " + message.destination());
                addressHandler.onMessageReceived(message, ctx);
            } else {
                System.out.println("ServerMessageBus: No address handler found for message to " + message.destination());
            }
        }
    }
} 