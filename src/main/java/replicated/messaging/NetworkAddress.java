package replicated.messaging;

public record NetworkAddress(String ipAddress, int port) {
    public NetworkAddress {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, but was: " + port);
        }
    }
} 