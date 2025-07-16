package replicated.network.id;

/**
 * Sealed interface for process identification in the distributed system.
 * Provides type-safe addressing that distinguishes between replicas and clients.
 */
public sealed interface ProcessId permits ReplicaId, ClientId {
    /**
     * Returns the human-readable name of this process.
     * Used for debugging and logging purposes.
     * 
     * @return the process name
     */
    String name();
}