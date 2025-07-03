package replicated.storage;

import replicated.future.ListenableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

class SimulatedStorageTest {
    
    private SimulatedStorage storage;
    private Random seededRandom;
    
    @BeforeEach
    void setUp() {
        seededRandom = new Random(42L); // Fixed seed for deterministic tests
        storage = new SimulatedStorage(seededRandom);
    }
    
    @Test
    void shouldCreateStorageWithRandomGenerator() {
        // Given random generator provided in setUp()
        // Then storage should be created successfully
        assertNotNull(storage);
    }
    
    @Test
    void shouldThrowExceptionForNullRandom() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> new SimulatedStorage(null));
    }
    
    @Test
    void shouldReturnPendingFutureForGetOperation() {
        // Given
        byte[] key = "test-key".getBytes();
        
        // When
        ListenableFuture<VersionedValue> future = storage.get(key);
        
        // Then
        assertNotNull(future);
        assertTrue(future.isPending());
    }
    
    @Test
    void shouldReturnPendingFutureForSetOperation() {
        // Given
        byte[] key = "test-key".getBytes();
        VersionedValue value = new VersionedValue("test-value".getBytes(), 1L);
        
        // When
        ListenableFuture<Boolean> future = storage.set(key, value);
        
        // Then
        assertNotNull(future);
        assertTrue(future.isPending());
    }
    
    @Test
    void shouldCompleteGetOperationAfterTick() {
        // Given
        byte[] key = "test-key".getBytes();
        ListenableFuture<VersionedValue> future = storage.get(key);
        
        AtomicReference<VersionedValue> result = new AtomicReference<>();
        future.onSuccess(result::set);
        
        // When
        storage.tick();
        
        // Then
        assertTrue(future.isCompleted());
        assertNull(result.get()); // Key doesn't exist yet
    }
    
    @Test
    void shouldCompleteSetOperationAfterTick() {
        // Given
        byte[] key = "test-key".getBytes();
        VersionedValue value = new VersionedValue("test-value".getBytes(), 1L);
        ListenableFuture<Boolean> future = storage.set(key, value);
        
        AtomicReference<Boolean> result = new AtomicReference<>();
        future.onSuccess(result::set);
        
        // When
        storage.tick();
        
        // Then
        assertTrue(future.isCompleted());
        assertTrue(result.get());
    }
    
    @Test
    void shouldPersistAndRetrieveValues() {
        // Given
        byte[] key = "test-key".getBytes();
        VersionedValue value = new VersionedValue("test-value".getBytes(), 1L);
        
        // When - set value
        ListenableFuture<Boolean> setFuture = storage.set(key, value);
        storage.tick();
        
        // Then - value should be stored
        assertTrue(setFuture.isCompleted());
        assertTrue(setFuture.getResult());
        
        // When - get value
        ListenableFuture<VersionedValue> getFuture = storage.get(key);
        storage.tick();
        
        // Then - value should be retrieved
        assertTrue(getFuture.isCompleted());
        assertEquals(value, getFuture.getResult());
    }
    
    @Test
    void shouldOverwriteExistingValues() {
        // Given
        byte[] key = "test-key".getBytes();
        VersionedValue value1 = new VersionedValue("first-value".getBytes(), 1L);
        VersionedValue value2 = new VersionedValue("second-value".getBytes(), 2L);
        
        // When - set first value
        storage.set(key, value1);
        storage.tick();
        
        // And - set second value
        storage.set(key, value2);
        storage.tick();
        
        // Then - should retrieve second value
        ListenableFuture<VersionedValue> getFuture = storage.get(key);
        storage.tick();
        
        assertEquals(value2, getFuture.getResult());
    }
    
    @Test
    void shouldHandleMultipleKeysIndependently() {
        // Given
        byte[] key1 = "key1".getBytes();
        byte[] key2 = "key2".getBytes();
        VersionedValue value1 = new VersionedValue("value1".getBytes(), 1L);
        VersionedValue value2 = new VersionedValue("value2".getBytes(), 2L);
        
        // When
        storage.set(key1, value1);
        storage.set(key2, value2);
        storage.tick();
        
        // Then
        ListenableFuture<VersionedValue> getFuture1 = storage.get(key1);
        ListenableFuture<VersionedValue> getFuture2 = storage.get(key2);
        storage.tick();
        
        assertEquals(value1, getFuture1.getResult());
        assertEquals(value2, getFuture2.getResult());
    }
    
    @Test
    void shouldSupportDelayedOperations() {
        // Given - storage with 2-tick delay
        storage = new SimulatedStorage(seededRandom, 2, 0.0);
        
        byte[] key = "test-key".getBytes();
        VersionedValue value = new VersionedValue("test-value".getBytes(), 1L);
        
        // When
        ListenableFuture<Boolean> setFuture = storage.set(key, value);
        
        // Then - should not complete after 1 tick
        storage.tick();
        assertTrue(setFuture.isPending());
        
        // But should complete after 2 ticks
        storage.tick();
        assertTrue(setFuture.isCompleted());
    }
    
    @Test
    void shouldProcessMultipleOperationsPerTick() {
        // Given
        byte[] key1 = "key1".getBytes();
        byte[] key2 = "key2".getBytes();
        VersionedValue value1 = new VersionedValue("value1".getBytes(), 1L);
        VersionedValue value2 = new VersionedValue("value2".getBytes(), 2L);
        
        // When
        ListenableFuture<Boolean> setFuture1 = storage.set(key1, value1);
        ListenableFuture<Boolean> setFuture2 = storage.set(key2, value2);
        storage.tick();
        
        // Then
        assertTrue(setFuture1.isCompleted());
        assertTrue(setFuture2.isCompleted());
        assertTrue(setFuture1.getResult());
        assertTrue(setFuture2.getResult());
    }
    
    @Test
    void shouldMaintainDeterministicBehavior() {
        // Given - two storage instances with same seed
        SimulatedStorage storage1 = new SimulatedStorage(new Random(123L));
        SimulatedStorage storage2 = new SimulatedStorage(new Random(123L));
        
        byte[] key = "test-key".getBytes();
        VersionedValue value = new VersionedValue("test-value".getBytes(), 1L);
        
        // When - perform same operations
        ListenableFuture<Boolean> future1 = storage1.set(key, value);
        ListenableFuture<Boolean> future2 = storage2.set(key, value);
        
        storage1.tick();
        storage2.tick();
        
        // Then - should have same results
        assertEquals(future1.isCompleted(), future2.isCompleted());
        assertEquals(future1.getResult(), future2.getResult());
    }
    
    @Test
    void shouldThrowExceptionForNullKey() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> storage.get(null));
        assertThrows(IllegalArgumentException.class, () -> storage.set(null, 
            new VersionedValue("value".getBytes(), 1L)));
    }
    
    @Test
    void shouldThrowExceptionForNullValue() {
        // Given
        byte[] key = "test-key".getBytes();
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> storage.set(key, null));
    }
} 