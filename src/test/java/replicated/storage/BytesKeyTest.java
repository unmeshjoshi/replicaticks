package replicated.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;

class BytesKeyTest {
    
    @Test
    void shouldCreateBytesKeyWithValidBytes() {
        // Given
        byte[] bytes = {1, 2, 3};
        
        // When
        BytesKey key = new BytesKey(bytes);
        
        // Then
        assertArrayEquals(bytes, key.bytes());
    }
    
    @Test
    void shouldThrowExceptionForNullBytes() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> new BytesKey(null));
    }
    
    @Test
    void shouldImplementContentBasedEquality() {
        // Given
        byte[] bytes1 = {1, 2, 3};
        byte[] bytes2 = {1, 2, 3};
        byte[] bytes3 = {4, 5, 6};
        
        BytesKey key1 = new BytesKey(bytes1);
        BytesKey key2 = new BytesKey(bytes2);
        BytesKey key3 = new BytesKey(bytes3);
        
        // When & Then
        assertEquals(key1, key2); // Same content
        assertNotEquals(key1, key3); // Different content
        assertNotEquals(key2, key3); // Different content
    }
    
    @Test
    void shouldImplementConsistentHashCode() {
        // Given
        byte[] bytes1 = {1, 2, 3};
        byte[] bytes2 = {1, 2, 3};
        byte[] bytes3 = {4, 5, 6};
        
        BytesKey key1 = new BytesKey(bytes1);
        BytesKey key2 = new BytesKey(bytes2);
        BytesKey key3 = new BytesKey(bytes3);
        
        // When & Then
        assertEquals(key1.hashCode(), key2.hashCode()); // Same content = same hash
        assertNotEquals(key1.hashCode(), key3.hashCode()); // Different content = different hash
    }
    
    @Test
    void shouldWorkAsMapKey() {
        // Given
        Map<BytesKey, String> map = new HashMap<>();
        byte[] keyBytes1 = {1, 2, 3};
        byte[] keyBytes2 = {1, 2, 3}; // Same content, different array
        byte[] keyBytes3 = {4, 5, 6};
        
        BytesKey key1 = new BytesKey(keyBytes1);
        BytesKey key2 = new BytesKey(keyBytes2);
        BytesKey key3 = new BytesKey(keyBytes3);
        
        // When
        map.put(key1, "value1");
        map.put(key3, "value3");
        
        // Then
        assertEquals("value1", map.get(key1));
        assertEquals("value1", map.get(key2)); // Same content should retrieve same value
        assertEquals("value3", map.get(key3));
        assertEquals(2, map.size()); // Should have 2 entries (key1 and key2 are equivalent)
    }
    
    @Test
    void shouldHandleEmptyByteArray() {
        // Given
        byte[] emptyBytes = {};
        
        // When
        BytesKey key = new BytesKey(emptyBytes);
        
        // Then
        assertArrayEquals(emptyBytes, key.bytes());
    }
    
    @Test
    void shouldHandleBinaryData() {
        // Given
        byte[] binaryData = {-128, -1, 0, 1, 127};
        
        // When
        BytesKey key = new BytesKey(binaryData);
        
        // Then
        assertArrayEquals(binaryData, key.bytes());
    }
    
    @Test
    void shouldMaintainEqualityAfterArrayModification() {
        // Given
        byte[] originalBytes = {1, 2, 3};
        byte[] copyBytes = {1, 2, 3};
        
        BytesKey key1 = new BytesKey(originalBytes);
        BytesKey key2 = new BytesKey(copyBytes);
        
        // When
        originalBytes[0] = 99; // Modify original array
        
        // Then
        // Keys should still be equal if the arrays had the same content at creation time
        // (This tests defensive copying behavior if implemented)
        assertEquals(key1, key2);
    }
    
    @Test
    void shouldHaveMeaningfulToString() {
        // Given
        byte[] bytes = {1, 2, 3};
        BytesKey key = new BytesKey(bytes);
        
        // When
        String string = key.toString();
        
        // Then
        assertNotNull(string);
        assertTrue(string.contains("BytesKey"));
        assertTrue(string.contains("[1, 2, 3]"));
    }
} 