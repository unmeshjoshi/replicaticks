package replicated.messaging;

import replicated.network.MessageContext;

/**
 * Optional extension to {@link MessageHandler} that gives implementors access
 * to the preserved {@link MessageContext}. Handlers that care about the exact
 * socket/channel a message arrived on (e.g. replicas that need to reply via
 * the same connection) should implement this interface. Others may ignore it
 * and only implement {@link MessageHandler}.
 */
public interface ContextualMessageHandler extends MessageHandler {
    void onMessageReceived(Message message, MessageContext ctx);
}
