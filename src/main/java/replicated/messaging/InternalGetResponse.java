package replicated.messaging;

import replicated.storage.VersionedValue;

import java.util.Objects;

public record InternalGetResponse(String key, VersionedValue value, String correlationId) {
    public InternalGetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
        // value can be null when not found
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InternalGetResponse that = (InternalGetResponse) obj;
        return Objects.equals(key, that.key) &&
               Objects.equals(value, that.value) &&
               Objects.equals(correlationId, that.correlationId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, value, correlationId);
    }
} 