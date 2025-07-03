package replicated.messaging;

import replicated.storage.VersionedValue;
import java.util.Objects;

public record GetResponse(String key, VersionedValue value, String requestId) {
    
    // Constructor for client responses (no requestId)
    public GetResponse(String key, VersionedValue value) {
        this(key, value, null);
    }
    
    public GetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        // value and requestId can be null
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GetResponse that = (GetResponse) obj;
        return Objects.equals(key, that.key) &&
               Objects.equals(value, that.value) &&
               Objects.equals(requestId, that.requestId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, value, requestId);
    }
} 