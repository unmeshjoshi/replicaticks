package replicated.network.topology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import replicated.network.id.ReplicaId;
import replicated.messaging.NetworkAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReplicaConfig record.
 */
class ReplicaConfigTest {

    @Test
    @DisplayName("ReplicaConfig should be created with valid parameters")
    void testValidReplicaConfigCreation() {
        ReplicaId replicaId = ReplicaId.of(1, "test-replica");
        NetworkAddress internodeAddr = new NetworkAddress("192.168.1.100", 8080);
        ReplicaConfig config = new ReplicaConfig(replicaId, internodeAddr);
        
        assertEquals(replicaId, config.replicaId());
        assertEquals(internodeAddr, config.address());
        assertEquals(new NetworkAddress("192.168.1.100", 8080), config.address());
    }
    
    @Test
    @DisplayName("ReplicaConfig should reject null replicaId")
    void testNullReplicaIdRejection() {
        NetworkAddress internodeAddr = new NetworkAddress("localhost", 8080);
        assertThrows(
            NullPointerException.class,
            () -> new ReplicaConfig(null, internodeAddr)
        );
    }
    
    @Test
    @DisplayName("ReplicaConfig should reject null internode address")
    void testNullInternodeAddressRejection() {
        ReplicaId replicaId = ReplicaId.of(1);
        assertThrows(
            NullPointerException.class,
            () -> new ReplicaConfig(replicaId, null)
        );
    }

    @Test
    @DisplayName("of factory method should create correct config")
    void testOfFactoryMethod() {
        ReplicaConfig config = ReplicaConfig.of(2, "192.168.1.100", 9000);
        
        assertEquals(ReplicaId.of(2), config.replicaId());
        assertEquals(new NetworkAddress("192.168.1.100", 9000), config.address());
    }
    
    @Test
    @DisplayName("index method should return correct index")
    void testIndexMethod() {
        ReplicaConfig config = ReplicaConfig.of(5, "localhost", 8080);
        assertEquals(5, config.index());
    }
}