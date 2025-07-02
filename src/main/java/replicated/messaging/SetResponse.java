package replicated.messaging;

import java.util.Objects;

public record SetResponse(String key, boolean success) {
    public SetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
    }
} 