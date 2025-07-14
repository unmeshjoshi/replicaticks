package replicated.messaging;

public record NetworkAddress(String ipAddress, int port) {
    public NetworkAddress {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, but was: " + port);
        }
    }

    public static NetworkAddress parse(String s) {
        if (s == null || !s.contains(":")) throw new IllegalArgumentException("Invalid address: " + s);
        String[] parts = s.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid address: " + s);
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);
        return new NetworkAddress(ip, port);
    }

    /**
     * Creates a {@link NetworkAddress} from a given {@link java.net.InetSocketAddress}.
     *
     * @param socketAddress the InetSocketAddress to convert (must not be {@code null})
     * @return a new {@code NetworkAddress} representing the same IP and port
     * @throws IllegalArgumentException if {@code socketAddress} is {@code null}
     */
    public static NetworkAddress from(java.net.InetSocketAddress socketAddress) {
        if (socketAddress == null) {
            throw new IllegalArgumentException("socketAddress cannot be null");
        }
        return new NetworkAddress(socketAddress.getAddress().getHostAddress(), socketAddress.getPort());
    }
}