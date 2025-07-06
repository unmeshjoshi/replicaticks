package replicated.messaging;

import replicated.network.MessageContext;

/**
 * Interface for components that can receive and handle messages.
 * All handlers MUST implement the context-aware variant which provides
 * additional metadata (e.g. original channel) via {@link MessageContext}.
 */
public interface MessageHandler {

    /**
     * Called when a message is delivered to this handler.
     * @param message the received message
     * @param ctx contextual information for the message (may be null in rare cases)
     */
    void onMessageReceived(Message message, MessageContext ctx);
}