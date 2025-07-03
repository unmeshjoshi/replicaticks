package replicated.messaging;

import replicated.network.Network;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MessageBus provides a higher-level messaging abstraction over the Network layer.
 * It handles component registration, message routing, and coordinates tick() calls.
 */
public class MessageBus {
    
    private final Network network;
    private final MessageCodec messageCodec;
    private final Map<NetworkAddress, MessageHandler> registeredHandlers;
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
        Message message = new Message(clientAddress, destination, messageType, payload);
        sendMessage(message);
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