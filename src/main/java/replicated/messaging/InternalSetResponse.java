package replicated.messaging;

import java.util.Objects;

public record InternalSetResponse(String key, boolean success, String correlationId) {
    public InternalSetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
    }
} 