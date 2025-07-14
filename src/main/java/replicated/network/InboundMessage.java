package replicated.network;

import replicated.messaging.Message;

/**
 * Represents an inbound message with its associated context.
 */
public record InboundMessage(Message message, MessageContext messageContext) {
} 