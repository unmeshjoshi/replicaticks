package replicated.messaging;

import org.junit.jupiter.api.Test;
import replicated.storage.VersionedValue;

import static org.junit.jupiter.api.Assertions.*;

class GetResponseTest {

    @Test
    void shouldCreateSuccessfulGetResponse() {
        // Given
        String key = "user:123";
        VersionedValue value = new VersionedValue("John Doe".getBytes(), 1L);
        
        // When
        GetResponse response = new GetResponse(key, value);
        
        // Then
        assertEquals(key, response.key());
        assertEquals(value, response.value());
        assertNotNull(response.value());
    }
    
    @Test
    void shouldCreateNotFoundGetResponse() {
        // Given
        String key = "user:999";
        
        // When
        GetResponse response = new GetResponse(key, null);
        
        // Then
        assertEquals(key, response.key());
        assertNull(response.value());
    }
    
    @Test
    void shouldCreateResponseWithNullValue() {
        // Given
        String key = "user:123";
        
        // When
        GetResponse response = new GetResponse(key, null);
        
        // Then
        assertEquals(key, response.key());
        assertNull(response.value());
    }
    
    @Test
    void shouldProvideEqualityBasedOnAllFields() {
        // Given
        String key = "user:123";
        VersionedValue value1 = new VersionedValue("John Doe".getBytes(), 1L);
        VersionedValue value2 = new VersionedValue("Jane Doe".getBytes(), 2L);
        
        GetResponse response1 = new GetResponse(key, value1);
        GetResponse response2 = new GetResponse(key, value1);
        GetResponse response3 = new GetResponse(key, value2);
        
        // When & Then
        assertEquals(response1, response2);
        assertNotEquals(response1, response3);
        assertEquals(response1.hashCode(), response2.hashCode());
    }
    
    @Test
    void shouldRejectNullKey() {
        // When & Then
        assertThrows(NullPointerException.class, () -> 
            new GetResponse(null, new VersionedValue("value".getBytes(), 1L)));
    }
} 