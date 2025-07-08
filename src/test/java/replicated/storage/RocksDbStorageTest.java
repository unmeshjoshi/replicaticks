package replicated.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import replicated.future.ListenableFuture;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbStorageTest {
    
    @TempDir
    Path tempDir;
    
    private RocksDbStorage storage;
    
    @BeforeEach
    void setUp() {
        storage = new RocksDbStorage(tempDir.toString());
    }
    
    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }
    
    /**
     * Utility method to run ticks until a condition is met or timeout occurs.
     * This ensures deterministic testing without Thread.sleep().
     */
    private void runUntil(Supplier<Boolean> condition, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (!condition.get()) {
            storage.tick();
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                fail("Timeout waiting for condition to be met");
            }
            // Small yield to prevent busy waiting
            Thread.yield();
        }
    }
    
    /**
     * Convenience method with default timeout of 5 seconds.
     */
    private void runUntil(Supplier<Boolean> condition) {
        runUntil(condition, 5000);
    }
    
    @Test
    void shouldCreateRocksDbStorageSuccessfully() {
        // Given/When
        RocksDbStorage storage = new RocksDbStorage(tempDir.resolve("test-db").toString());
        
        // Then
        assertNotNull(storage);
        
        // Cleanup
        storage.close();
    }
    
    @Test
    void shouldStoreAndRetrieveValue() {
        // Given
        String key = "test-key";
        VersionedValue value = new VersionedValue("test-value".getBytes(), 12345L);
        
        AtomicReference<Boolean> setResult = new AtomicReference<>();
        AtomicReference<VersionedValue> getResult = new AtomicReference<>();
        
        // When - store value
        ListenableFuture<Boolean> setFuture = storage.set(key.getBytes(), value);
        setFuture.onSuccess(setResult::set);
        
        // Wait for async operation to complete
        runUntil(() -> setResult.get() != null);
        
        // Then - set should succeed
        assertTrue(setResult.get());
        
        // When - retrieve value
        ListenableFuture<VersionedValue> getFuture = storage.get(key.getBytes());
        getFuture.onSuccess(getResult::set);
        
        // Wait for async operation to complete
        runUntil(() -> getResult.get() != null);
        
        // Then - should retrieve the same value
        assertArrayEquals(value.value(), getResult.get().value());
        assertEquals(value.timestamp(), getResult.get().timestamp());
    }
    
    @Test
    void shouldReturnNullForNonExistentKey() {
        // Given
        String key = "non-existent-key";
        AtomicReference<VersionedValue> result = new AtomicReference<>();
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        
        // When
        ListenableFuture<VersionedValue> future = storage.get(key.getBytes());
        future.onSuccess(value -> {
            result.set(value);
            completed.set(true);
        });
        
        // Wait for async operation to complete
        runUntil(() -> completed.get());
        
        // Then
        assertNull(result.get());
    }
    
    @Test
    void shouldThrowExceptionForNullKey() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> storage.get(null));
        
        VersionedValue value = new VersionedValue("test".getBytes(), 123L);
        assertThrows(IllegalArgumentException.class, () -> storage.set(null, value));
    }
    
    @Test
    void shouldThrowExceptionForNullValue() {
        // Given/When/Then
        assertThrows(IllegalArgumentException.class, () -> 
            storage.set("key".getBytes(), null));
    }
    
    @Test
    void shouldHandleMultipleOperations() {
        // Given
        String key1 = "key1";
        String key2 = "key2";
        VersionedValue value1 = new VersionedValue("value1".getBytes(), 111L);
        VersionedValue value2 = new VersionedValue("value2".getBytes(), 222L);
        
        AtomicReference<Boolean> setResult1 = new AtomicReference<>();
        AtomicReference<Boolean> setResult2 = new AtomicReference<>();
        
        // When - store multiple values
        ListenableFuture<Boolean> setFuture1 = storage.set(key1.getBytes(), value1);
        ListenableFuture<Boolean> setFuture2 = storage.set(key2.getBytes(), value2);
        
        setFuture1.onSuccess(setResult1::set);
        setFuture2.onSuccess(setResult2::set);
        
        // Wait for async operations to complete
        runUntil(() -> setResult1.get() != null && setResult2.get() != null);
        
        // Then - both should succeed
        assertTrue(setResult1.get());
        assertTrue(setResult2.get());
        
        // When - retrieve both values
        AtomicReference<VersionedValue> getResult1 = new AtomicReference<>();
        AtomicReference<VersionedValue> getResult2 = new AtomicReference<>();
        
        ListenableFuture<VersionedValue> getFuture1 = storage.get(key1.getBytes());
        ListenableFuture<VersionedValue> getFuture2 = storage.get(key2.getBytes());
        
        getFuture1.onSuccess(getResult1::set);
        getFuture2.onSuccess(getResult2::set);
        
        // Wait for async operations to complete
        runUntil(() -> getResult1.get() != null && getResult2.get() != null);
        
        // Then - should retrieve correct values
        assertArrayEquals(value1.value(), getResult1.get().value());
        assertEquals(value1.timestamp(), getResult1.get().timestamp());
        
        assertArrayEquals(value2.value(), getResult2.get().value());
        assertEquals(value2.timestamp(), getResult2.get().timestamp());
    }
    
    @Test
    void shouldOverwriteExistingValue() {
        // Given
        String key = "test-key";
        VersionedValue originalValue = new VersionedValue("original".getBytes(), 111L);
        VersionedValue newValue = new VersionedValue("updated".getBytes(), 222L);
        
        AtomicReference<Boolean> setResult1 = new AtomicReference<>();
        AtomicReference<Boolean> setResult2 = new AtomicReference<>();
        
        // When - store original value
        ListenableFuture<Boolean> setFuture1 = storage.set(key.getBytes(), originalValue);
        setFuture1.onSuccess(setResult1::set);
        
        // Wait for async operation to complete
        runUntil(() -> setResult1.get() != null);
        
        // Verify first set completed
        assertTrue(setResult1.get());
        
        // When - overwrite with new value
        ListenableFuture<Boolean> setFuture2 = storage.set(key.getBytes(), newValue);
        setFuture2.onSuccess(setResult2::set);
        
        // Wait for async operation to complete
        runUntil(() -> setResult2.get() != null);
        
        // Verify second set completed
        assertTrue(setResult2.get());
        
        // When - retrieve value
        AtomicReference<VersionedValue> result = new AtomicReference<>();
        ListenableFuture<VersionedValue> future = storage.get(key.getBytes());
        future.onSuccess(result::set);
        
        // Wait for async operation to complete
        runUntil(() -> result.get() != null);
        
        // Then - should have the new value
        assertArrayEquals(newValue.value(), result.get().value());
        assertEquals(newValue.timestamp(), result.get().timestamp());
    }
    
    @Test
    void shouldProcessTickWithoutOperations() {
        // Given/When/Then - should not throw
        assertDoesNotThrow(() -> storage.tick());
    }
    
    @Test
    void shouldHandleSerializationCorrectly() {
        // Given - test edge cases for serialization
        VersionedValue emptyValue = new VersionedValue(new byte[0], 0L);
        VersionedValue maxTimestamp = new VersionedValue("test".getBytes(), Long.MAX_VALUE);
        VersionedValue minTimestamp = new VersionedValue("test".getBytes(), Long.MIN_VALUE);
        
        AtomicReference<Boolean> emptyResult = new AtomicReference<>();
        AtomicReference<Boolean> maxResult = new AtomicReference<>();
        AtomicReference<Boolean> minResult = new AtomicReference<>();
        
        // When - store all values
        storage.set("empty".getBytes(), emptyValue).onSuccess(emptyResult::set);
        storage.set("max".getBytes(), maxTimestamp).onSuccess(maxResult::set);
        storage.set("min".getBytes(), minTimestamp).onSuccess(minResult::set);
        
        // Wait for all operations to complete
        runUntil(() -> emptyResult.get() != null && maxResult.get() != null && minResult.get() != null);
        
        // Then - all should succeed
        assertTrue(emptyResult.get());
        assertTrue(maxResult.get());
        assertTrue(minResult.get());
    }
    
    @Test
    void shouldCloseResourcesGracefully() {
        // Given/When/Then - should not throw
        assertDoesNotThrow(() -> storage.close());
    }
} 