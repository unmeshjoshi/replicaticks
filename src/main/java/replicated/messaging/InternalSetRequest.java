package replicated.messaging;

import java.util.Arrays;
import java.util.Objects;

public record InternalSetRequest(String key, byte[] value, long timestamp, String correlationId) {
    public InternalSetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        InternalSetRequest that = (InternalSetRequest) obj;
        return timestamp == that.timestamp &&
               Objects.equals(key, that.key) &&
               Arrays.equals(value, that.value) &&
               Objects.equals(correlationId, that.correlationId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, Arrays.hashCode(value), timestamp, correlationId);
    }
} 