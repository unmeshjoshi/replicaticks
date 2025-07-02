package replicated.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionedValueTest {

    @Test
    void shouldCreateVersionedValueWithValueAndTimestamp() {
        // Given
        byte[] value = "test-data".getBytes();
        long timestamp = System.currentTimeMillis();
        
        // When
        VersionedValue versionedValue = new VersionedValue(value, timestamp);
        
        // Then
        assertArrayEquals(value, versionedValue.value());
        assertEquals(timestamp, versionedValue.timestamp());
    }
    
    @Test
    void shouldRejectNullValue() {
        // Given
        long timestamp = System.currentTimeMillis();
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new VersionedValue(null, timestamp));
    }
    
    @Test
    void shouldAllowEmptyByteArray() {
        // Given
        byte[] emptyValue = new byte[0];
        long timestamp = System.currentTimeMillis();
        
        // When
        VersionedValue versionedValue = new VersionedValue(emptyValue, timestamp);
        
        // Then
        assertArrayEquals(emptyValue, versionedValue.value());
        assertEquals(timestamp, versionedValue.timestamp());
    }
    
    @Test
    void shouldAllowZeroTimestamp() {
        // Given
        byte[] value = "data".getBytes();
        long timestamp = 0L;
        
        // When
        VersionedValue versionedValue = new VersionedValue(value, timestamp);
        
        // Then
        assertEquals(timestamp, versionedValue.timestamp());
    }
    
    @Test
    void shouldAllowNegativeTimestamp() {
        // Given - might happen in simulation with relative time
        byte[] value = "data".getBytes();
        long timestamp = -1000L;
        
        // When
        VersionedValue versionedValue = new VersionedValue(value, timestamp);
        
        // Then
        assertEquals(timestamp, versionedValue.timestamp());
    }
    
    @Test
    void shouldProvideEqualityBasedOnValueAndTimestamp() {
        // Given
        byte[] value1 = "same-content".getBytes();
        byte[] value2 = "same-content".getBytes(); // Same content, different array instance
        byte[] value3 = "different-content".getBytes();
        long timestamp1 = 1000L;
        long timestamp2 = 2000L;
        
        VersionedValue vv1 = new VersionedValue(value1, timestamp1);
        VersionedValue vv2 = new VersionedValue(value2, timestamp1); // Same content & timestamp
        VersionedValue vv3 = new VersionedValue(value1, timestamp2); // Same content, different timestamp
        VersionedValue vv4 = new VersionedValue(value3, timestamp1); // Different content, same timestamp
        
        // When & Then
        assertEquals(vv1, vv2); // Same content and timestamp
        assertNotEquals(vv1, vv3); // Different timestamp
        assertNotEquals(vv1, vv4); // Different content
        assertEquals(vv1.hashCode(), vv2.hashCode()); // Same hash for equal objects
    }
    
    @Test
    void shouldCompareVersionsByTimestamp() {
        // Given
        byte[] value = "data".getBytes();
        VersionedValue older = new VersionedValue(value, 1000L);
        VersionedValue newer = new VersionedValue(value, 2000L);
        VersionedValue same = new VersionedValue(value, 1000L);
        
        // When & Then
        assertTrue(older.timestamp() < newer.timestamp());
        assertTrue(newer.timestamp() > older.timestamp());
        assertEquals(older.timestamp(), same.timestamp());
    }
    
    @Test
    void shouldHandleLargeByteArrays() {
        // Given
        byte[] largeValue = new byte[1024]; // 1KB
        for (int i = 0; i < largeValue.length; i++) {
            largeValue[i] = (byte) (i % 256);
        }
        long timestamp = System.currentTimeMillis();
        
        // When
        VersionedValue versionedValue = new VersionedValue(largeValue, timestamp);
        
        // Then
        assertArrayEquals(largeValue, versionedValue.value());
        assertEquals(timestamp, versionedValue.timestamp());
    }
} 