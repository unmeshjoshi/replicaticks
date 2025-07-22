package replicated.network.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record representing a client identifier in the distributed system.
 * Each client is identified by a unique UUID and has a human-readable name.
 */
public record ClientId(UUID uuid, String name) implements ProcessId {
    
    /**
     * Compact constructor with validation.
     * 
     * @param uuid the client UUID (must not be null)
     * @param name the client name (must not be null)
     * @throws NullPointerException if uuid or name is null
     */
    public ClientId {
        Objects.requireNonNull(uuid, "Client UUID cannot be null");
        Objects.requireNonNull(name, "Client name cannot be null");
    }
    
    /**
     * Factory method to create a ClientId with a random UUID and generated name.
     * The generated name uses the first 8 characters of the UUID for readability.
     * 
     * @return a new ClientId instance with random UUID
     */
    public static ClientId random() {
        UUID uuid = UUID.randomUUID();
        String shortId = uuid.toString().substring(0, 8);
        return new ClientId(uuid, "client-" + shortId);
    }
    
    /**
     * Factory method to create a ClientId with a random UUID and custom name.
     * 
     * @param prefix the client name (must not be null)
     * @return a new ClientId instance with random UUID and specified name
     * @throws NullPointerException if name is null
     */
    public static ClientId random(String prefix) {
        Objects.requireNonNull(prefix, "Client name cannot be null");
        UUID uuid1 = UUID.randomUUID();
        return of(uuid1.toString(), prefix);
    }
    
    /**
     * Factory method to create a ClientId from a UUID string and name.
     * 
     * @param uuidString the UUID as a string (must be valid UUID format)
     * @param prefix the client name (must not be null)
     * @return a new ClientId instance
     * @throws IllegalArgumentException if uuidString is not a valid UUID
     * @throws NullPointerException if uuidString or name is null
     */
    public static ClientId of(String uuidString, String prefix) {
        Objects.requireNonNull(uuidString, "UUID string cannot be null");
        Objects.requireNonNull(prefix, "Client name cannot be null");
        try {
            UUID uuid = UUID.fromString(uuidString);
            return new ClientId(uuid, prefix + "-" + uuidString.substring(0, 8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID format: " + uuidString, e);
        }
    }
}