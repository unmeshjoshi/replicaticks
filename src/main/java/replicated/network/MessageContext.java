package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.NetworkAddress;

import java.nio.channels.SocketChannel;

/**
 * Context information for messages, preserving the source channel and metadata
 * to enable proper request-response correlation and routing.
 * 
 * This follows production patterns from Kafka and Cassandra where message
 * context is preserved throughout the processing pipeline.
 */
public class MessageContext {
    
    private final Message message;
    private final SocketChannel sourceChannel;
    private final NetworkAddress sourceAddress;
    private final long timestamp;
    private final boolean isInbound;
    private String correlationId;
    
    /**
     * Creates a context for an inbound message (received from a client).
     */
    public MessageContext(Message message, SocketChannel sourceChannel, NetworkAddress sourceAddress) {
        this.message = message;
        this.sourceChannel = sourceChannel;
        this.sourceAddress = sourceAddress;
        this.timestamp = System.currentTimeMillis();
        this.isInbound = true;
    }
    
    /**
     * Creates a context for an outbound message (sending to a server).
     */
    public MessageContext(Message message) {
        this.message = message;
        this.sourceChannel = null;
        this.sourceAddress = null;
        this.timestamp = System.currentTimeMillis();
        this.isInbound = false;
    }
    
    public Message getMessage() {
        return message;
    }
    
    public SocketChannel getSourceChannel() {
        return sourceChannel;
    }
    
    public NetworkAddress getSourceAddress() {
        return sourceAddress;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public boolean isInbound() {
        return isInbound;
    }
    
    public boolean isOutbound() {
        return !isInbound;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
    
    /**
     * Determines if this message is likely a response to a previous request.
     * This is a heuristic based on message type and context.
     */
    public boolean isResponse() {
        if (message == null) return false;
        
        String messageType = message.messageType().toString();
        return messageType.contains("RESPONSE") || 
               (isInbound && messageType.contains("INTERNAL"));
    }
    
    /**
     * Determines if this message is likely a request.
     */
    public boolean isRequest() {
        if (message == null) return false;
        
        String messageType = message.messageType().toString();
        return messageType.contains("REQUEST");
    }
    
    /**
     * Creates a response context that preserves the original request's channel context.
     * This enables responses to be routed back via the same channel the request came from.
     */
    public MessageContext createResponseContext(Message responseMessage) {
        if (!isInbound || sourceChannel == null) {
            throw new IllegalStateException("Cannot create response context: not an inbound message with source channel");
        }
        
        MessageContext responseContext = new MessageContext(responseMessage, sourceChannel, sourceAddress);
        responseContext.setCorrelationId(this.correlationId);
        return responseContext;
    }
    
    public boolean hasSourceChannel() {
        return sourceChannel != null;
    }
    
    public boolean canRouteResponse() {
        return isInbound && sourceChannel != null && sourceChannel.isOpen();
    }
    
    @Override
    public String toString() {
        return String.format("MessageContext{msg=%s→%s, type=%s, inbound=%s, hasChannel=%s, timestamp=%d}", 
                           message != null ? message.source() : "null",
                           message != null ? message.destination() : "null",
                           message != null ? message.messageType() : "null",
                           isInbound, 
                           sourceChannel != null,
                           timestamp);
    }
} 