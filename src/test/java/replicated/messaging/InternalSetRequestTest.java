package replicated.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternalSetRequestTest {

    @Test
    void shouldCreateInternalSetRequest() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        long timestamp = 1234567890L;
        String correlationId = "req-456";
        
        // When
        InternalSetRequest request = new InternalSetRequest(key, value, timestamp, correlationId);
        
        // Then
        assertEquals(key, request.key());
        assertArrayEquals(value, request.value());
        assertEquals(timestamp, request.timestamp());
        assertEquals(correlationId, request.correlationId());
    }
    
    @Test
    void shouldProvideEqualityBasedOnAllFields() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        long timestamp = 1234567890L;
        String correlationId = "req-456";
        
        InternalSetRequest request1 = new InternalSetRequest(key, value, timestamp, correlationId);
        InternalSetRequest request2 = new InternalSetRequest(key, value, timestamp, correlationId);
        InternalSetRequest request3 = new InternalSetRequest(key, value, timestamp + 1, correlationId);
        
        // When & Then
        assertEquals(request1, request2);
        assertNotEquals(request1, request3);
        assertEquals(request1.hashCode(), request2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalSetRequest(null, "value".getBytes(), 123L, "req-123"));
    }
    
    @Test
    void shouldRejectNullValue() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalSetRequest("key", null, 123L, "req-123"));
    }
    
    @Test
    void shouldRejectNullCorrelationId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new InternalSetRequest("key", "value".getBytes(), 123L, null));
    }
} 