package replicated.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkAddressTest {

    @Test
    void shouldCreateNetworkAddressWithValidIpAndPort() {
        // Given
        String ipAddress = "192.168.1.1";
        int port = 8080;
        
        // When
        NetworkAddress address = new NetworkAddress(ipAddress, port);
        
        // Then
        assertEquals(ipAddress, address.ipAddress());
        assertEquals(port, address.port());
    }
    
    @Test
    void shouldProvideEqualityBasedOnIpAndPort() {
        // Given
        NetworkAddress address1 = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress address2 = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress address3 = new NetworkAddress("192.168.1.2", 8080);
        
        // When & Then
        assertEquals(address1, address2);
        assertNotEquals(address1, address3);
        assertEquals(address1.hashCode(), address2.hashCode());
    }
    
    @Test
    void shouldRejectNegativePortNumbers() {
        // Given
        String ipAddress = "192.168.1.1";
        int invalidPort = -1;
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new NetworkAddress(ipAddress, invalidPort));
    }
    
    @Test
    void shouldRejectPortZero() {
        // Given
        String ipAddress = "192.168.1.1";
        int invalidPort = 0;
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new NetworkAddress(ipAddress, invalidPort));
    }
    
    @Test
    void shouldRejectPortNumbersAbove65535() {
        // Given
        String ipAddress = "192.168.1.1";
        int invalidPort = 65536;
        
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> 
            new NetworkAddress(ipAddress, invalidPort));
    }
    
    @Test
    void shouldAcceptValidPortRange() {
        // Given & When & Then - should not throw
        assertDoesNotThrow(() -> new NetworkAddress("192.168.1.1", 1));
        assertDoesNotThrow(() -> new NetworkAddress("192.168.1.1", 8080));
        assertDoesNotThrow(() -> new NetworkAddress("192.168.1.1", 65535));
    }
} 