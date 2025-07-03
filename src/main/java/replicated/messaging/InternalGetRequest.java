package replicated.messaging;

import java.util.Objects;

public record InternalGetRequest(String key, String correlationId) {
    public InternalGetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
    }
} 