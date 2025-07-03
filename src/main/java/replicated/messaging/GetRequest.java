package replicated.messaging;

import java.util.Objects;

public record GetRequest(String key, String requestId) {
    
    // Constructor for client requests (no requestId)
    public GetRequest(String key) {
        this(key, null);
    }
    
    public GetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        // requestId can be null for client requests
    }
} 