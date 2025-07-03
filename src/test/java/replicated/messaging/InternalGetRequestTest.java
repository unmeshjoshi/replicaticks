package replicated.messaging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InternalGetRequestTest {

    @Test
    void shouldCreateInternalGetRequest() {
        // Given
        String key = "user:123";
        String correlationId = "req-456";
        
        // When
        InternalGetRequest request = new InternalGetRequest(key, correlationId);
        
        // Then
        assertEquals(key, request.key());
        assertEquals(correlationId, request.correlationId());
    }
    
    @Test
    void shouldProvideEqualityBasedOnAllFields() {
        // Given
        String key = "user:123";
        String correlationId = "req-456";
        
        InternalGetRequest request1 = new InternalGetRequest(key, correlationId);
        InternalGetRequest request2 = new InternalGetRequest(key, correlationId);
        InternalGetRequest request3 = new InternalGetRequest(key, "different-id");
        
        // When & Then
        assertEquals(request1, request2);
        assertNotEquals(request1, request3);
        assertEquals(request1.hashCode(), request2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalGetRequest(null, "req-123"));
    }
    
    @Test
    void shouldRejectNullCorrelationId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalGetRequest("key", null));
    }
} 