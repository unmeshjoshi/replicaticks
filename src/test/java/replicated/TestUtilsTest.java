package replicated;

import org.junit.jupiter.api.Test;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TestUtils utility methods.
 */
public class TestUtilsTest {

    @Test
    public void shouldGenerateRandomAddresses() throws IOException {
        Set<NetworkAddress> addresses = new HashSet<>();
        
        // Generate multiple addresses and verify they're unique
        for (int i = 0; i < 10; i++) {
            NetworkAddress address = TestUtils.randomAddress();
            assertNotNull(address, "Generated address should not be null");
            assertEquals("127.0.0.1", address.ipAddress(), "IP address should be 127.0.0.1");
            assertTrue(address.port() > 0, "Port should be positive");
            assertTrue(address.port() <= 65535, "Port should be within valid range");
            
            // Verify uniqueness
            assertTrue(addresses.add(address), "Generated address should be unique: " + address);
        }
        
        assertEquals(10, addresses.size(), "Should generate 10 unique addresses");
    }

    @Test
    public void shouldGenerateAddressesWithDifferentPorts() throws IOException {
        NetworkAddress address1 = TestUtils.randomAddress();
        NetworkAddress address2 = TestUtils.randomAddress();
        
        assertNotEquals(address1.port(), address2.port(), "Generated addresses should have different ports");
        assertEquals(address1.ipAddress(), address2.ipAddress(), "IP addresses should be the same (127.0.0.1)");
    }
} 