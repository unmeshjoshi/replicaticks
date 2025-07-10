package replicated.messaging;

/**
 * Message types for the replicated key-value store.
 *
 */
public enum MessageType {
    
    /** Client request to get a value by key */
    CLIENT_GET_REQUEST(Category.CLIENT_REQUEST),
    /** Client request to set a key-value pair */
    CLIENT_SET_REQUEST(Category.CLIENT_REQUEST),
    
    /** Response to client GET requests */
    CLIENT_GET_RESPONSE(Category.CLIENT_RESPONSE),
    /** Response to client SET requests */
    CLIENT_SET_RESPONSE(Category.CLIENT_RESPONSE),

    // === INTERNAL MESSAGE TYPES (Server-to-Server) ===
        /** Internal request to get a value from a replica */
    INTERNAL_GET_REQUEST(Category.INTERNAL_REQUEST),
    /** Internal request to set a value on a replica */
    INTERNAL_SET_REQUEST(Category.INTERNAL_REQUEST),
     /** Response to internal GET request */
    INTERNAL_GET_RESPONSE(Category.INTERNAL_RESPONSE),
    /** Response to internal SET request */
    INTERNAL_SET_RESPONSE(Category.INTERNAL_RESPONSE),
    
    // === SYSTEM MESSAGE TYPES ===
    
    /** Heartbeat/ping message */
    PING_REQUEST(Category.SYSTEM_REQUEST),
    
    /** Response to ping */
    PING_RESPONSE(Category.SYSTEM_RESPONSE),
    
    /** Generic failure response */
    FAILURE_RESPONSE(Category.SYSTEM_RESPONSE),
    
    // === PAXOS MESSAGE TYPES ===
    
    /** Client request to propose a value via Paxos */
    PAXOS_PROPOSE_REQUEST(Category.CLIENT_REQUEST),
    /** Response to client propose request */
    PAXOS_PROPOSE_RESPONSE(Category.CLIENT_RESPONSE),
    
    /** Paxos Phase 1a: Prepare request */
    PAXOS_PREPARE_REQUEST(Category.INTERNAL_REQUEST),
    /** Paxos Phase 1b: Promise response */
    PAXOS_PROMISE_RESPONSE(Category.INTERNAL_RESPONSE),
    
    /** Paxos Phase 2a: Accept request */
    PAXOS_ACCEPT_REQUEST(Category.INTERNAL_REQUEST),
    /** Paxos Phase 2b: Accepted response */
    PAXOS_ACCEPTED_RESPONSE(Category.INTERNAL_RESPONSE),
    
    /** Paxos commit notification */
    PAXOS_COMMIT_REQUEST(Category.INTERNAL_REQUEST);
    
    // === ENUM FIELDS ===
    
    private final Category category;

    // === CONSTRUCTORS ===
    
    MessageType(Category category) {
        this.category = category;
    }
    
    // === PUBLIC METHODS ===
    
    /**
     * Returns true if this message type represents a response.
     * 
     * This method derives the response status from the message category:
     * - CLIENT_RESPONSE, INTERNAL_RESPONSE, SYSTEM_RESPONSE → true
     * - CLIENT_REQUEST, INTERNAL_REQUEST, SYSTEM_REQUEST → false
     * 
     * @return true if this is a response message type
     */
    public boolean isResponse() {
        return category.isResponse();
    }
    
    /**
     * Returns true if this message type represents a request.
     * 
     * @return true if this is a request message type
     */
    public boolean isRequest() {
        return !isResponse();
    }

    public Category getCategory() {
        return category;
    }
    
    /**
     * @return true if this is a client request or response
     */
    public boolean isClientMessage() {
        return category == Category.CLIENT_REQUEST || category == Category.CLIENT_RESPONSE;
    }
    
    /**
     * Returns true if this is an internal server-to-server message.
     * @return true if this is an internal request or response
     */
    public boolean isInternalMessage() {
        return category == Category.INTERNAL_REQUEST || category == Category.INTERNAL_RESPONSE;
    }
    
    /**
     * Returns true if this is a system message (ping, failure, etc.).
     * 
     * @return true if this is a system message
     */
    public boolean isSystemMessage() {
        return category == Category.SYSTEM_REQUEST || category == Category.SYSTEM_RESPONSE;
    }
    
    // === INNER CLASSES ===
    
    /**
     * Categories for message types, similar to Cassandra's Stage classification.
     */
    public enum Category {
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