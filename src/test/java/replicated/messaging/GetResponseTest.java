package replicated.messaging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GetResponseTest {

    @Test
    void shouldCreateSuccessfulGetResponse() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        
        // When
        GetResponse response = new GetResponse(key, value, true);
        
        // Then
        assertEquals(key, response.key());
        assertArrayEquals(value, response.value());
        assertTrue(response.found());
    }
    
    @Test
    void shouldCreateNotFoundGetResponse() {
        // Given
        String key = "user:999";
        
        // When
        GetResponse response = new GetResponse(key, null, false);
        
        // Then
        assertEquals(key, response.key());
        assertNull(response.value());
        assertFalse(response.found());
    }
    
    @Test
    void shouldProvideEqualityBasedOnAllFields() {
        // Given
        String key = "user:123";
        byte[] value = "John Doe".getBytes();
        
        GetResponse response1 = new GetResponse(key, value, true);
        GetResponse response2 = new GetResponse(key, value, true);
        GetResponse response3 = new GetResponse(key, value, false);
        
        // When & Then
        assertEquals(response1, response2);
        assertNotEquals(response1, response3);
        assertEquals(response1.hashCode(), response2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new GetResponse(null, "value".getBytes(), true));
    }
} 