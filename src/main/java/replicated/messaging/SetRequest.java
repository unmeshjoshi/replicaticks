package replicated.messaging;

import java.util.Arrays;
import java.util.Objects;

public record SetRequest(String key, byte[] value, long timestamp, String requestId) {
    
    // Constructor for client requests (no timestamp/requestId)
    public SetRequest(String key, byte[] value) {
        this(key, value, System.currentTimeMillis(), null);
    }
    
    // Constructor for internal requests with timestamp (no requestId)
    public SetRequest(String key, byte[] value, long timestamp) {
        this(key, value, timestamp, null);
    }
    
    public SetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        // timestamp and requestId can be null/0 for client requests
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SetRequest that = (SetRequest) obj;
        return timestamp == that.timestamp &&
               Objects.equals(key, that.key) &&
               Arrays.equals(value, that.value) &&
               Objects.equals(requestId, that.requestId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, Arrays.hashCode(value), timestamp, requestId);
    }
} 