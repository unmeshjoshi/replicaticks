package replicated.messaging;

/**
 * Interface for components that can receive and handle messages.
 * Implementations include Replica and Client classes.
 */
public interface MessageHandler {
    
    /**
     * Called when a message is received for this handler.
     * 
     * @param message the received message
     */
    void onMessageReceived(Message message);
} 