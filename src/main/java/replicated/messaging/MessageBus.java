package replicated.messaging;

import replicated.network.Network;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MessageBus provides a higher-level messaging abstraction over the Network layer.
 * It handles component registration, message routing, and coordinates tick() calls.
 */
public class MessageBus {
    
    private final Network network;
    private final MessageCodec messageCodec;
    private final Map<NetworkAddress, MessageHandler> registeredHandlers;
    
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
     * Registers a message handler for the given network address.
     * When messages are received for this address, they will be routed to the handler.
     * 
     * @param address the network address to register the handler for
     * @param handler the message handler to receive messages
     */
    public void registerHandler(NetworkAddress address, MessageHandler handler) {
        registeredHandlers.put(address, handler);
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
                Message message = new Message(source, recipient, messageType, payload);
                sendMessage(message);
            }
        }
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
        for (Map.Entry<NetworkAddress, MessageHandler> entry : registeredHandlers.entrySet()) {
            NetworkAddress address = entry.getKey();
            MessageHandler handler = entry.getValue();
            
            List<Message> messages = network.receive(address);
            for (Message message : messages) {
                handler.onMessageReceived(message);
            }
        }
    }
} 