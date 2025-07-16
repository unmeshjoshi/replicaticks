package replicated.network.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProcessId interface and its implementations.
 */
class ProcessIdTest {

    @Test
    void testProcessIdPolymorphism() {
        ProcessId replicaId = new ReplicaId((byte) 1, "test-replica");
        ProcessId clientId = ClientId.random("test-client");
        
        // Test that both implementations provide name() method
        assertNotNull(replicaId.name());
        assertNotNull(clientId.name());
        
        // Test type checking
        assertTrue(replicaId instanceof ReplicaId);
        assertTrue(clientId instanceof ClientId);
        
        // Test sealed interface - only ReplicaId and ClientId should be permitted
        assertInstanceOf(ProcessId.class, replicaId);
        assertInstanceOf(ProcessId.class, clientId);
    }
    
    @Test
    void testProcessIdSwitchExpression() {
        ProcessId replicaId = new ReplicaId((byte) 1);
        ProcessId clientId = ClientId.random();
        
        // Test that sealed interface works with switch expressions
        String replicaType = switch (replicaId) {
            case ReplicaId r -> "replica";
            case ClientId c -> "client";
        };
        assertEquals("replica", replicaType);
        
        String clientType = switch (clientId) {
            case ReplicaId r -> "replica";
            case ClientId c -> "client";
        };
        assertEquals("client", clientType);
    }
}