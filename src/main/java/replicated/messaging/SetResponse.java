package replicated.messaging;

import java.util.Objects;

public record SetResponse(String key, boolean success, String requestId) {
    
    // Constructor for client responses (no requestId)
    public SetResponse(String key, boolean success) {
        this(key, success, null);
    }
    
    public SetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        // requestId can be null for client responses
    }
} 