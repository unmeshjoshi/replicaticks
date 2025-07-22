package replicated.network.id;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientId record.
 */
class ClientIdTest {

    @Test
    @DisplayName("ClientId should be created with valid UUID and name")
    void testValidClientIdCreation() {
        UUID testUuid = UUID.randomUUID();
        ClientId clientId = new ClientId(testUuid, "test-client");
        
        assertEquals(testUuid, clientId.uuid());
        assertEquals("test-client", clientId.name());
    }
    
    @Test
    @DisplayName("ClientId should reject null UUID")
    void testNullUuidRejection() {
        assertThrows(
            NullPointerException.class,
            () -> new ClientId(null, "test-client")
        );
    }
    
    @Test
    @DisplayName("ClientId should reject null name")
    void testNullNameRejection() {
        assertThrows(
            NullPointerException.class,
            () -> new ClientId(UUID.randomUUID(), null)
        );
    }
    
    @Test
    @DisplayName("ClientId.random() should generate unique instances")
    void testRandomFactory() {
        ClientId client1 = ClientId.random();
        ClientId client2 = ClientId.random();
        
        // Should have different UUIDs
        assertNotEquals(client1.uuid(), client2.uuid());
        assertNotEquals(client1, client2);
        
        // Names should follow pattern
        assertTrue(client1.name().startsWith("client-"));
        assertTrue(client2.name().startsWith("client-"));
        
        // Names should be different (based on different UUIDs)
        assertNotEquals(client1.name(), client2.name());
    }
    
    @Test
    @DisplayName("ClientId.random() should generate statistically unique UUIDs")
    void testRandomUniqueness() {
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        
        // Generate many random ClientIds
        for (int i = 0; i < 1000; i++) {
            ClientId clientId = ClientId.random();
            uuids.add(clientId.uuid());
            names.add(clientId.name());
        }
        
        // All should be unique
        assertEquals(1000, uuids.size());
        assertEquals(1000, names.size());
    }
    
    @Test
    @DisplayName("ClientId.random(String) should use custom name")
    void testRandomWithCustomName() {
        ClientId clientId = ClientId.random("custom-client");
        
        assertTrue(clientId.name().startsWith("custom-client-"));
        assertNotNull(clientId.uuid());
    }
    
    @Test
    @DisplayName("ClientId.random(String) should reject null name")
    void testRandomWithNullName() {
        assertThrows(
            NullPointerException.class,
            () -> ClientId.random(null)
        );
    }
    
    @Test
    @DisplayName("ClientId.of should create ClientId from UUID string")
    void testOfStringFactory() {
        String uuidString = "550e8400-e29b-41d4-a716-446655440000";
        ClientId clientId = ClientId.of(uuidString, "test-client");
        
        assertEquals(UUID.fromString(uuidString), clientId.uuid());
        assertTrue(clientId.name().startsWith("test-client-"));
    }
    
    @Test
    @DisplayName("ClientId.of should reject invalid UUID string")
    void testOfInvalidUuidString() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ClientId.of("not-a-uuid", "test-client")
        );
        assertTrue(exception.getMessage().contains("Invalid UUID format"));
    }
    
    @Test
    @DisplayName("ClientId.of should reject null parameters")
    void testOfNullParameters() {
        String validUuid = "550e8400-e29b-41d4-a716-446655440000";
        
        assertThrows(
            NullPointerException.class,
            () -> ClientId.of(null, "test-client")
        );
        
        assertThrows(
            NullPointerException.class,
            () -> ClientId.of(validUuid, null)
        );
    }
    
    @Test
    @DisplayName("ClientId should have proper equals and hashCode")
    void testEqualsAndHashCode() {
        UUID uuid1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID uuid2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        
        ClientId client1 = new ClientId(uuid1, "test");
        ClientId client2 = new ClientId(uuid1, "test");
        ClientId client3 = new ClientId(uuid2, "test");
        ClientId client4 = new ClientId(uuid1, "different");
        
        // Test equality
        assertEquals(client1, client2);
        assertNotEquals(client1, client3);
        assertNotEquals(client1, client4);
        
        // Test hash code consistency
        assertEquals(client1.hashCode(), client2.hashCode());
        
        // Test reflexivity
        assertEquals(client1, client1);
        
        // Test null and different type
        assertNotEquals(client1, null);
        assertNotEquals(client1, "not a client");
    }
    
    @Test
    @DisplayName("ClientId should implement ProcessId interface")
    void testProcessIdInterface() {
        ClientId clientId = ClientId.random("interface-test");
        ProcessId processId = clientId;
        
        assertTrue(processId.name().startsWith("interface-test"));
        assertInstanceOf(ProcessId.class, clientId);
    }
    
    @Test
    @DisplayName("ClientId toString should be meaningful")
    void testToString() {
        UUID testUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ClientId clientId = new ClientId(testUuid, "test-client");
        String toString = clientId.toString();
        
        assertTrue(toString.contains("550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(toString.contains("test-client"));
    }
    
    @Test
    @DisplayName("ClientId random name should use first 8 characters of UUID")
    void testRandomNameFormat() {
        ClientId clientId = ClientId.random();
        String expectedPrefix = clientId.uuid().toString().substring(0, 8);
        String expectedName = "client-" + expectedPrefix;
        
        assertEquals(expectedName, clientId.name());
    }
}