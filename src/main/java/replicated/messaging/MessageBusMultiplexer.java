package replicated.messaging;

import replicated.network.MessageCallback;
import replicated.network.MessageContext;
import replicated.network.Network;

import java.util.ArrayList;
import java.util.List;

/**
 * MessageBusMultiplexer routes messages from a single network to multiple MessageBus instances.
 * This solves the issue where multiple MessageBus instances need to receive messages from the same network,
 * but the Network interface only supports a single callback handler.
 */
public class MessageBusMultiplexer implements MessageCallback {
    
    private final List<MessageCallback> messageBuses;
    private final Network network;
    
    /**
     * Creates a MessageBusMultiplexer for the given network.
     * 
     * @param network the network to receive messages from
     */
    public MessageBusMultiplexer(Network network) {
        this.network = network;
        this.messageBuses = new ArrayList<>();
        
        // Register this multiplexer as the single callback handler for the network
        network.registerMessageHandler(this);
    }
    
    /**
     * Registers a MessageBus to receive messages from the network.
     * The MessageBus should implement MessageCallback to receive messages.
     * 
     * @param messageBus the MessageBus to register
     */
    public void registerMessageBus(MessageCallback messageBus) {
        if (messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null");
        }
        messageBuses.add(messageBus);
    }
    
    /**
     * Unregisters a MessageBus from receiving messages.
     * 
     * @param messageBus the MessageBus to unregister
     */
    public void unregisterMessageBus(MessageCallback messageBus) {
        if (messageBus == null) {
            throw new IllegalArgumentException("MessageBus cannot be null");
        }
        messageBuses.remove(messageBus);
    }
    
    /**
     * Callback method that receives messages from the network and routes them to all registered MessageBus instances.
     * Each MessageBus will decide whether to process the message based on its own routing logic.
     */
    @Override
    public void onMessage(Message message, MessageContext context) {
        // Route the message to all registered MessageBus instances
        // Each MessageBus will decide whether to process the message based on its routing criteria
        for (MessageCallback messageBus : messageBuses) {
            try {
                messageBus.onMessage(message, context);
            } catch (Exception e) {
                System.err.println("MessageBusMultiplexer: Error routing message to MessageBus: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
} 