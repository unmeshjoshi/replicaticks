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
        NetworkAddress clientRpcAddr = new NetworkAddress("192.168.1.100", 9080);
        ReplicaConfig config = new ReplicaConfig(replicaId, internodeAddr, clientRpcAddr);
        
        assertEquals(replicaId, config.replicaId());
        assertEquals(internodeAddr, config.internodeAddress());
        assertEquals(clientRpcAddr, config.clientRpcAddress());
        assertEquals("192.168.1.100:8080", config.internodeAddressString());
        assertEquals("192.168.1.100:9080", config.clientRpcAddressString());
    }
    
    @Test
    @DisplayName("ReplicaConfig should reject null replicaId")
    void testNullReplicaIdRejection() {
        NetworkAddress internodeAddr = new NetworkAddress("localhost", 8080);
        NetworkAddress clientRpcAddr = new NetworkAddress("localhost", 9080);
        assertThrows(
            NullPointerException.class,
            () -> new ReplicaConfig(null, internodeAddr, clientRpcAddr)
        );
    }
    
    @Test
    @DisplayName("ReplicaConfig should reject null internode address")
    void testNullInternodeAddressRejection() {
        ReplicaId replicaId = ReplicaId.of(1);
        NetworkAddress clientRpcAddr = new NetworkAddress("localhost", 9080);
        assertThrows(
            NullPointerException.class,
            () -> new ReplicaConfig(replicaId, null, clientRpcAddr)
        );
    }
    
    @Test
    @DisplayName("ReplicaConfig should reject null client RPC address")
    void testNullClientRpcAddressRejection() {
        ReplicaId replicaId = ReplicaId.of(1);
        NetworkAddress internodeAddr = new NetworkAddress("localhost", 8080);
        assertThrows(
            NullPointerException.class,
            () -> new ReplicaConfig(replicaId, internodeAddr, null)
        );
    }
    
    @Test
    @DisplayName("ReplicaConfig should reject same internode and client RPC addresses")
    void testSameAddressRejection() {
        ReplicaId replicaId = ReplicaId.of(1);
        NetworkAddress sameAddr = new NetworkAddress("localhost", 8080);
        assertThrows(
            IllegalArgumentException.class,
            () -> new ReplicaConfig(replicaId, sameAddr, sameAddr)
        );
    }
    
    @Test
    @DisplayName("localhost factory method should create correct config")
    void testLocalhostFactoryMethod() {
        ReplicaConfig config = ReplicaConfig.localhost(1, 8080);
        
        assertEquals(ReplicaId.of(1), config.replicaId());
        assertEquals(new NetworkAddress("localhost", 8080), config.internodeAddress());
        assertEquals(new NetworkAddress("localhost", 9080), config.clientRpcAddress());
        assertEquals("localhost:8080", config.internodeAddressString());
        assertEquals("localhost:9080", config.clientRpcAddressString());
    }
    
    @Test
    @DisplayName("of factory method should create correct config")
    void testOfFactoryMethod() {
        ReplicaConfig config = ReplicaConfig.of(2, "192.168.1.100", 9000);
        
        assertEquals(ReplicaId.of(2), config.replicaId());
        assertEquals(new NetworkAddress("192.168.1.100", 9000), config.internodeAddress());
        assertEquals(new NetworkAddress("192.168.1.100", 10000), config.clientRpcAddress());
        assertEquals("192.168.1.100:9000", config.internodeAddressString());
        assertEquals("192.168.1.100:10000", config.clientRpcAddressString());
    }
    
    @Test
    @DisplayName("index method should return correct index")
    void testIndexMethod() {
        ReplicaConfig config = ReplicaConfig.localhost(5, 8080);
        assertEquals(5, config.index());
    }
}