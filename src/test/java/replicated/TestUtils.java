package replicated;

import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Utility methods for testing.
 */
public class TestUtils {

    /**
     * Creates a random network address using a free port on localhost.
     * 
     * @return a NetworkAddress with "127.0.0.1" and a random available port
     * @throws IOException if no free port can be found
     */
    public static NetworkAddress randomAddress() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            return new NetworkAddress("127.0.0.1", port);
        }
    }
} 