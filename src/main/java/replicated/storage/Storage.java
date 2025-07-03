package replicated.storage;

import replicated.future.ListenableFuture;

/**
 * Interface for asynchronous key-value storage operations.
 * All operations return ListenableFuture to support non-blocking I/O
 * in the single-threaded event loop.
 */
public interface Storage {
    
    /**
     * Retrieves a value for the given key.
     * 
     * @param key the key to retrieve
     * @return a future containing the versioned value, or null if not found
     */
    ListenableFuture<VersionedValue> get(byte[] key);
    
    /**
     * Stores a value for the given key.
     * 
     * @param key the key to store
     * @param value the versioned value to store
     * @return a future containing true if successful, false otherwise
     */
    ListenableFuture<Boolean> set(byte[] key, VersionedValue value);
    
    /**
     * Advances the storage simulation by one tick.
     * This method processes pending operations and completes their futures.
     * Should be called by the simulation loop.
     */
    void tick();
} 