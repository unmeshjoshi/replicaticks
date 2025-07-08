package replicated.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GetRequestTest {

    @Test
    void shouldCreateGetRequestWithKey() {
        // Given
        String key = "user:123";
        
        // When
        GetRequest request = new GetRequest(key);
        
        // Then
        assertEquals(key, request.key());
    }
    
    @Test
    void shouldProvideEqualityBasedOnKey() {
        // Given
        GetRequest request1 = new GetRequest("user:123");
        GetRequest request2 = new GetRequest("user:123");
        GetRequest request3 = new GetRequest("user:456");
        
        // When & Then
        assertEquals(request1, request2);
        assertNotEquals(request1, request3);
        assertEquals(request1.hashCode(), request2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> new GetRequest(null));
    }
} 