package replicated.network;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

/**
 * Manages the state of network connections and message queues.
 * Thread-safe state container for NioNetwork.
 * 
 * FOLLOWS "ONE CONNECTION PER DIRECTION" PRINCIPLE:
 * - inboundConnections: Client connections to us (for receiving from clients, sending responses)
 * - outboundConnections: Our connections to replicas (for sending to replicas)
 * - Server channels: For accepting new client connections
 * 
 * QUEUE DESIGN:
 * - Per-source inbound queues: Fair processing, isolation from slow sources
 * - Per-destination outbound queues: Fair sending, isolation from slow destinations
 * - Pending messages: For messages waiting for connection establishment
 */
public final class NetworkState {

    // Server sockets bound to specific addresses
    private final Map<NetworkAddress, ServerSocketChannel> serverChannels = new ConcurrentHashMap<>();

    private final MessageCodec codec;
    final Connections connections;
    private final NetworkConfig config;
    private final MetricsCollector metricsCollector;

    public NetworkState(MessageCodec codec, NetworkConfig config, MetricsCollector metricsCollector) {
        this.codec = codec;
        this.config = config;
        this.metricsCollector = metricsCollector;
        connections = new Connections(config, codec, metricsCollector);
    }
    // Getters for server channels
    public Map<NetworkAddress, ServerSocketChannel> getServerChannels() {
        return serverChannels;
    }

    public void putServerChannel(NetworkAddress address, ServerSocketChannel channel) {
        serverChannels.put(address, channel);
    }

    public ServerSocketChannel removeServerChannel(NetworkAddress address) {
        return serverChannels.remove(address);
    }

    // Getters for INBOUND connections (client connections to us)

    // Getters for OUTBOUND connections (our connections to replicas)



    public SocketChannel removeOutboundConnection(NetworkAddress address) {
        return connections.remove(address);
    }

    // Helper methods for PER-SOURCE inbound message queues

    // Helper methods for PER-DESTINATION outbound message queues
    public int getOutboundQueueSize() {
        return connections.getTotalQueueSize();
    }

    public void addOutboundMessage(Message message) {
       connections.addOutboundMessage(message);
    }

    // Get queue size for a specific destination
    public int getOutboundQueueSizeForDestination(NetworkAddress destination) {
        return connections.getQueueSizeForDestination(destination);
    }

    public void clearPendingMessages(NetworkAddress address) {
        connections.clearPendingMessages(address);
    }

    public void clearAllPendingMessages() {
        connections.clearAllPendingMessages();
    }

    public SocketChannel getClientChannel(NetworkAddress destination) {
        return connections.getChannel(destination);
    }
}