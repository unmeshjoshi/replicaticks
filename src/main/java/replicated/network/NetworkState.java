package replicated.network;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

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

    final InboundConnections inboundConnections = new InboundConnections();
    private final MessageCodec codec;
    final OutboundConnections outboundConnections;
    private final NetworkConfig config;

    public NetworkState(MessageCodec codec, NetworkConfig config) {
        this.codec = codec;
        this.config = config;
        outboundConnections = new OutboundConnections(config, codec);
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
    public OutboundConnections getOutboundConnections() {
        return outboundConnections;
    }


    public SocketChannel removeOutboundConnection(NetworkAddress address) {
        return outboundConnections.remove(address);
    }

    // Helper methods for PER-SOURCE inbound message queues

    // Helper methods for PER-DESTINATION outbound message queues
    public int getOutboundQueueSize() {
        return outboundConnections.getTotalQueueSize();
    }

    public void addOutboundMessage(Message message) {
       outboundConnections.addOutboundMessage(message);
    }

    // Get queue size for a specific destination
    public int getOutboundQueueSizeForDestination(NetworkAddress destination) {
        return outboundConnections.getQueueSizeForDestination(destination);
    }

    public void clearPendingMessages(NetworkAddress address) {
        outboundConnections.clearPendingMessages(address);
    }

    public void clearAllPendingMessages() {
        outboundConnections.clearAllPendingMessages();
    }

    public SocketChannel getClientChannel(NetworkAddress destination) {
        return outboundConnections.getChannel(destination);
    }
}