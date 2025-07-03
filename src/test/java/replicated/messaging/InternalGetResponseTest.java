package replicated.messaging;

import replicated.storage.VersionedValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InternalGetResponseTest {

    @Test
    void shouldCreateInternalGetResponse() {
        // Given
        String key = "user:123";
        VersionedValue value = new VersionedValue("John Doe".getBytes(), 1L);
        String correlationId = "req-456";
        
        // When
        InternalGetResponse response = new InternalGetResponse(key, value, correlationId);
        
        // Then
        assertEquals(key, response.key());
        assertEquals(value, response.value());
        assertEquals(correlationId, response.correlationId());
    }
    
    @Test
    void shouldCreateResponseWithNullValue() {
        // Given
        String key = "user:123";
        String correlationId = "req-456";
        
        // When
        InternalGetResponse response = new InternalGetResponse(key, null, correlationId);
        
        // Then
        assertEquals(key, response.key());
        assertNull(response.value());
        assertEquals(correlationId, response.correlationId());
    }
    
    @Test
    void shouldProvideEqualityBasedOnAllFields() {
        // Given
        String key = "user:123";
        VersionedValue value = new VersionedValue("John Doe".getBytes(), 1L);
        String correlationId = "req-456";
        
        InternalGetResponse response1 = new InternalGetResponse(key, value, correlationId);
        InternalGetResponse response2 = new InternalGetResponse(key, value, correlationId);
        InternalGetResponse response3 = new InternalGetResponse(key, value, "different-id");
        
        // When & Then
        assertEquals(response1, response2);
        assertNotEquals(response1, response3);
        assertEquals(response1.hashCode(), response2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalGetResponse(null, new VersionedValue("value".getBytes(), 1L), "req-123"));
    }
    
    @Test
    void shouldRejectNullCorrelationId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalGetResponse("key", new VersionedValue("value".getBytes(), 1L), null));
    }
} 