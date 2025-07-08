package replicated.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternalSetResponseTest {

    @Test
    void shouldCreateInternalSetResponse() {
        // Given
        String key = "user:123";
        boolean success = true;
        String correlationId = "req-456";
        
        // When
        InternalSetResponse response = new InternalSetResponse(key, success, correlationId);
        
        // Then
        assertEquals(key, response.key());
        assertEquals(success, response.success());
        assertEquals(correlationId, response.correlationId());
    }
    
    @Test
    void shouldCreateFailureResponse() {
        // Given
        String key = "user:123";
        boolean success = false;
        String correlationId = "req-456";
        
        // When
        InternalSetResponse response = new InternalSetResponse(key, success, correlationId);
        
        // Then
        assertEquals(key, response.key());
        assertFalse(response.success());
        assertEquals(correlationId, response.correlationId());
    }
    
    @Test
    void shouldProvideEqualityBasedOnAllFields() {
        // Given
        String key = "user:123";
        String correlationId = "req-456";
        
        InternalSetResponse response1 = new InternalSetResponse(key, true, correlationId);
        InternalSetResponse response2 = new InternalSetResponse(key, true, correlationId);
        InternalSetResponse response3 = new InternalSetResponse(key, false, correlationId);
        
        // When & Then
        assertEquals(response1, response2);
        assertNotEquals(response1, response3);
        assertEquals(response1.hashCode(), response2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalSetResponse(null, true, "req-123"));
    }
    
    @Test
    void shouldRejectNullCorrelationId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalSetResponse("key", true, null));
    }
} 