package replicated.messaging;

import java.util.Objects;

public record GetRequest(String key) {
    public GetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
    }
} 