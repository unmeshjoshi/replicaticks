package replicated.messaging;

import replicated.storage.VersionedValue;

import java.util.Objects;

public record GetResponse(String key, VersionedValue value) {
    public GetResponse {
        Objects.requireNonNull(key, "Key cannot be null");
        // value can be null when not found
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GetResponse that = (GetResponse) obj;
        return Objects.equals(key, that.key) &&
               Objects.equals(value, that.value);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
} 