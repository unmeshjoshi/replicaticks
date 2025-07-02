package replicated.messaging;

import java.util.Arrays;
import java.util.Objects;

public record SetRequest(String key, byte[] value) {
    public SetRequest {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SetRequest that = (SetRequest) obj;
        return Objects.equals(key, that.key) && Arrays.equals(value, that.value);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, Arrays.hashCode(value));
    }
} 