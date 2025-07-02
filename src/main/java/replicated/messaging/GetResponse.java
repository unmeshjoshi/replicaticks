package replicated.messaging;

import java.util.Arrays;
import java.util.Objects;

public record GetResponse(String key, byte[] value, boolean found) {
    public GetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        // value can be null when not found
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GetResponse that = (GetResponse) obj;
        return found == that.found &&
               Objects.equals(key, that.key) &&
               Arrays.equals(value, that.value);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, Arrays.hashCode(value), found);
    }
} 