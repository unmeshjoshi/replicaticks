package replicated.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SetRequestTest {

    @Test
    void shouldCreateSetRequestWithKeyAndValue() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        
        // When
        SetRequest request = new SetRequest(key, value);
        
        // Then
        assertEquals(key, request.key());
        assertArrayEquals(value, request.value());
    }
    
    @Test
    void shouldProvideEqualityBasedOnKeyAndValue() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        
        SetRequest request1 = new SetRequest(key, value);
        SetRequest request2 = new SetRequest(key, value);
        SetRequest request3 = new SetRequest(key, "Jane Doe".getBytes());
        
        // When & Then
        assertEquals(request1, request2);
        assertNotEquals(request1, request3);
        assertEquals(request1.hashCode(), request2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new SetRequest(null, "value".getBytes()));
    }
    
    @Test
    void shouldRejectNullValue() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new SetRequest("key", null));
    }
} 