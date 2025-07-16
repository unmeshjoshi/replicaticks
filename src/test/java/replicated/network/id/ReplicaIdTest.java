package replicated.network.id;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReplicaId record.
 */
class ReplicaIdTest {

    @Test
    @DisplayName("ReplicaId should be created with valid index and name")
    void testValidReplicaIdCreation() {
        ReplicaId replicaId = new ReplicaId(5, "test-replica");
        
        assertEquals(5, replicaId.index());
        assertEquals("test-replica", replicaId.name());
    }
    
    @Test
    @DisplayName("ReplicaId should handle high index values correctly")
    void testHighIndexValues() {
        // Test that high index values work correctly with int
        ReplicaId replicaId = ReplicaId.of(255, "test-replica-255");
        
        assertEquals(255, replicaId.index());
        assertEquals("test-replica-255", replicaId.name());
    }
    
    @Test
    @DisplayName("ReplicaId should reject null name")
    void testNullNameRejection() {
        assertThrows(
            NullPointerException.class,
            () -> new ReplicaId(1, null)
        );
    }
    
    @Test
    @DisplayName("ReplicaId convenience constructor should generate default name")
    void testConvenienceConstructor() {
        ReplicaId replicaId = new ReplicaId(3);
        
        assertEquals(3, replicaId.index());
        assertEquals("replica-3", replicaId.name());
    }
    
    @Test
    @DisplayName("ReplicaId.of(int) should create ReplicaId from integer")
    void testOfIntegerFactory() {
        ReplicaId replicaId = ReplicaId.of(42);
        
        assertEquals(42, replicaId.index());
        assertEquals("replica-42", replicaId.name());
    }
    
    @Test
    @DisplayName("ReplicaId.of(int, String) should create ReplicaId with custom name")
    void testOfIntegerWithNameFactory() {
        ReplicaId replicaId = ReplicaId.of(10, "custom-replica");
        
        assertEquals(10, replicaId.index());
        assertEquals("custom-replica", replicaId.name());
    }
    
    @Test
    @DisplayName("ReplicaId.of should reject negative index")
    void testOfNegativeIndexValidation() {
        // Test negative values
        IllegalArgumentException negativeException = assertThrows(
            IllegalArgumentException.class,
            () -> ReplicaId.of(-1)
        );
        assertTrue(negativeException.getMessage().contains("non-negative"));
    }
    
    @Test
    @DisplayName("ReplicaId.of should accept boundary values")
    void testOfBoundaryValues() {
        // Test boundary values work
        Assertions.assertDoesNotThrow(() -> ReplicaId.of(0));
        Assertions.assertDoesNotThrow(() -> ReplicaId.of(255));
        
        // Verify the created instances
        ReplicaId zero = ReplicaId.of(0);
        ReplicaId max = ReplicaId.of(255);
        
        assertEquals(0, zero.index());
        assertEquals(255, max.index());
        assertEquals("replica-0", zero.name());
        assertEquals("replica-255", max.name());
    }
    
    @Test
    @DisplayName("ReplicaId should have proper equals and hashCode")
    void testEqualsAndHashCode() {
        ReplicaId replica1 = new ReplicaId(1, "test");
        ReplicaId replica2 = new ReplicaId(1, "test");
        ReplicaId replica3 = new ReplicaId(2, "test");
        ReplicaId replica4 = new ReplicaId(1, "different");
        
        // Test equality
        assertEquals(replica1, replica2);
        assertNotEquals(replica1, replica3);
        assertNotEquals(replica1, replica4);
        
        // Test hash code consistency
        assertEquals(replica1.hashCode(), replica2.hashCode());
        
        // Test reflexivity
        assertEquals(replica1, replica1);
        
        // Test null and different type
        assertNotEquals(replica1, null);
        assertNotEquals(replica1, "not a replica");
    }
    
    @Test
    @DisplayName("ReplicaId should implement ProcessId interface")
    void testProcessIdInterface() {
        ReplicaId replicaId = new ReplicaId(7, "interface-test");
        ProcessId processId = replicaId;
        
        assertEquals("interface-test", processId.name());
        assertInstanceOf(ProcessId.class, replicaId);
    }
    
    @Test
    @DisplayName("ReplicaId toString should be meaningful")
    void testToString() {
        ReplicaId replicaId = new ReplicaId(5, "test-replica");
        String toString = replicaId.toString();
        
        assertTrue(toString.contains("5"));
        assertTrue(toString.contains("test-replica"));
    }
}