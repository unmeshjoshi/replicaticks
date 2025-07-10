package replicated.messaging;

/**
 * Interface for message types in the replicated messaging system.
 * 
 * This interface allows library consumers to define their own message types
 * while maintaining compatibility with the core messaging infrastructure.
 * 
 * Design inspired by Cassandra's Verb system and the Strategy pattern.
 */
public interface MessageTypeInterface {
    
    /**
     * Returns the unique identifier for this message type.
     * This should be globally unique across all message types in the system.
     * 
     * @return unique string identifier
     */
    String getId();
    
    /**
     * Returns the category of this message type.
     * 
     * @return the message category
     */
    Category getCategory();
    
    /**
     * Returns true if this message type represents a response.
     * 
     * @return true if this is a response message type
     */
    default boolean isResponse() {
        return getCategory().isResponse();
    }
    
    /**
     * Returns true if this message type represents a request.
     * 
     * @return true if this is a request message type
     */
    default boolean isRequest() {
        return !isResponse();
    }
    
    /**
     * @return true if this is a client request or response
     */
    default boolean isClientMessage() {
        Category cat = getCategory();
        return cat == Category.CLIENT_REQUEST || cat == Category.CLIENT_RESPONSE;
    }
    
    /**
     * Returns true if this is an internal server-to-server message.
     * @return true if this is an internal request or response
     */
    default boolean isInternalMessage() {
        Category cat = getCategory();
        return cat == Category.INTERNAL_REQUEST || cat == Category.INTERNAL_RESPONSE;
    }
    
    /**
     * Returns true if this is a system message (ping, failure, etc.).
     * 
     * @return true if this is a system message
     */
    default boolean isSystemMessage() {
        Category cat = getCategory();
        return cat == Category.SYSTEM_REQUEST || cat == Category.SYSTEM_RESPONSE;
    }
    
    /**
     * Returns the timeout for this message type in milliseconds.
     * Default implementation returns 5 seconds.
     * 
     * @return timeout in milliseconds
     */
    default long getTimeoutMs() {
        return 5000; // 5 second default
    }
    
    /**
     * Categories for message types, similar to Cassandra's Stage classification.
     */
    enum Category {
        /** Client-originated requests */
        CLIENT_REQUEST,
        
        /** Responses to client requests */
        CLIENT_RESPONSE,
        
        /** Internal server-to-server requests */
        INTERNAL_REQUEST,
        
        /** Responses to internal requests */
        INTERNAL_RESPONSE,
        
        /** System requests (ping, health checks, etc.) */
        SYSTEM_REQUEST,
        
        /** System responses */
        SYSTEM_RESPONSE;
        
        /**
         * Returns true if this category represents response messages.
         * 
         * @return true if this is a response category
         */
        public boolean isResponse() {
            return this == CLIENT_RESPONSE || 
                   this == INTERNAL_RESPONSE || 
                   this == SYSTEM_RESPONSE;
        }
        
        /**
         * Returns true if this category represents request messages.
         * 
         * @return true if this is a request category
         */
        public boolean isRequest() {
            return !isResponse();
        }
    }
} 