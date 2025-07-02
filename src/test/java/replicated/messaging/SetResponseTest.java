package replicated.messaging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetResponseTest {

    @Test
    void shouldCreateSuccessfulSetResponse() {
        // Given
        String key = "user:123";
        
        // When
        SetResponse response = new SetResponse(key, true);
        
        // Then
        assertEquals(key, response.key());
        assertTrue(response.success());
    }
    
    @Test
    void shouldCreateFailedSetResponse() {
        // Given
        String key = "user:123";
        
        // When
        SetResponse response = new SetResponse(key, false);
        
        // Then
        assertEquals(key, response.key());
        assertFalse(response.success());
    }
    
    @Test
    void shouldProvideEqualityBasedOnKeyAndSuccess() {
        // Given
        SetResponse response1 = new SetResponse("user:123", true);
        SetResponse response2 = new SetResponse("user:123", true);
        SetResponse response3 = new SetResponse("user:123", false);
        
        // When & Then
        assertEquals(response1, response2);
        assertNotEquals(response1, response3);
        assertEquals(response1.hashCode(), response2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new SetResponse(null, true));
    }
} 