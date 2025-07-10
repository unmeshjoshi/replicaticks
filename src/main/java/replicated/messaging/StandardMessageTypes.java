package replicated.messaging;

/**
 * Standard message types provided by the replicated library.
 * 
 * These are the core message types needed for the distributed key-value store.
 * Library consumers can use these directly or create their own by implementing
 * MessageTypeInterface.
 * 
 * This approach allows for extensibility while maintaining type safety.
 */
public final class StandardMessageTypes {
    
    // Prevent instantiation
    private StandardMessageTypes() {}
    
    // === CLIENT MESSAGE TYPES ===
    
    /** Client request to get a value by key */
    public static final MessageTypeInterface CLIENT_GET_REQUEST = new StandardMessageType(
        "CLIENT_GET_REQUEST", 
        MessageTypeInterface.Category.CLIENT_REQUEST,
        5000L
    );
    
    /** Client request to set a key-value pair */
    public static final MessageTypeInterface CLIENT_SET_REQUEST = new StandardMessageType(
        "CLIENT_SET_REQUEST", 
        MessageTypeInterface.Category.CLIENT_REQUEST,
        5000L
    );
    
    /** Response to client requests (both GET and SET) */
    public static final MessageTypeInterface CLIENT_RESPONSE = new StandardMessageType(
        "CLIENT_RESPONSE", 
        MessageTypeInterface.Category.CLIENT_RESPONSE,
        0L // Responses don't timeout
    );

    // === INTERNAL MESSAGE TYPES (Server-to-Server) ===
    
    /** Internal request to get a value from a replica */
    public static final MessageTypeInterface INTERNAL_GET_REQUEST = new StandardMessageType(
        "INTERNAL_GET_REQUEST", 
        MessageTypeInterface.Category.INTERNAL_REQUEST,
        3000L // Shorter timeout for internal operations
    );
    
    /** Internal request to set a value on a replica */
    public static final MessageTypeInterface INTERNAL_SET_REQUEST = new StandardMessageType(
        "INTERNAL_SET_REQUEST", 
        MessageTypeInterface.Category.INTERNAL_REQUEST,
        3000L
    );
    
    /** Response to internal GET request */
    public static final MessageTypeInterface INTERNAL_GET_RESPONSE = new StandardMessageType(
        "INTERNAL_GET_RESPONSE", 
        MessageTypeInterface.Category.INTERNAL_RESPONSE,
        0L
    );
    
    /** Response to internal SET request */
    public static final MessageTypeInterface INTERNAL_SET_RESPONSE = new StandardMessageType(
        "INTERNAL_SET_RESPONSE", 
        MessageTypeInterface.Category.INTERNAL_RESPONSE,
        0L
    );
    
    // === SYSTEM MESSAGE TYPES ===
    
    /** Heartbeat/ping message */
    public static final MessageTypeInterface PING_REQUEST = new StandardMessageType(
        "PING_REQUEST", 
        MessageTypeInterface.Category.SYSTEM_REQUEST,
        1000L // Quick timeout for pings
    );
    
    /** Response to ping */
    public static final MessageTypeInterface PING_RESPONSE = new StandardMessageType(
        "PING_RESPONSE", 
        MessageTypeInterface.Category.SYSTEM_RESPONSE,
        0L
    );
    
    /** Generic failure response */
    public static final MessageTypeInterface FAILURE_RESPONSE = new StandardMessageType(
        "FAILURE_RESPONSE", 
        MessageTypeInterface.Category.SYSTEM_RESPONSE,
        0L
    );
    
    /**
     * Internal implementation of MessageTypeInterface for standard library types.
     */
    private static final class StandardMessageType implements MessageTypeInterface {
        private final String id;
        private final Category category;
        private final long timeoutMs;
        
        StandardMessageType(String id, Category category, long timeoutMs) {
            this.id = id;
            this.category = category;
            this.timeoutMs = timeoutMs;
        }
        
        @Override
        public String getId() {
            return id;
        }
        
        @Override
        public Category getCategory() {
            return category;
        }
        
        @Override
        public long getTimeoutMs() {
            return timeoutMs;
        }
        
        @Override
        public String toString() {
            return id;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MessageTypeInterface)) return false;
            MessageTypeInterface other = (MessageTypeInterface) obj;
            return id.equals(other.getId());
        }
        
        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
} 