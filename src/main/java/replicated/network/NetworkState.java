package replicated.network;

import java.net.InetSocketAddress;
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

    // INBOUND CONNECTIONS: Client connections to us (for receiving from clients, sending responses)
    private final Map<NetworkAddress, SocketChannel> inboundConnections = new ConcurrentHashMap<>();

    // OUTBOUND CONNECTIONS: Our connections to other nodes (for sending to replicas)
    private final Map<NetworkAddress, SocketChannel> outboundConnections = new ConcurrentHashMap<>();

    // PER-SOURCE INBOUND QUEUES: Separate queue for each source for fair processing
    private final Map<NetworkAddress, BlockingQueue<InboundMessage>> inboundQueues = new ConcurrentHashMap<>();

    // PER-DESTINATION OUTBOUND QUEUES: Separate queue for each destination for fair sending
    private final Map<NetworkAddress, BlockingQueue<Message>> outboundQueues = new ConcurrentHashMap<>();

    // Queue for messages pending connection establishment
    private final Map<NetworkAddress, Queue<Message>> pendingMessages = new ConcurrentHashMap<>();

    // Backpressure management
    private volatile boolean backpressureEnabled = false;

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
    public Map<NetworkAddress, SocketChannel> getInboundConnections() {
        return inboundConnections;
    }

    public SocketChannel getInboundConnection(NetworkAddress address) {
        return inboundConnections.get(address);
    }

    public void putInboundConnection(NetworkAddress address, SocketChannel channel) {
        inboundConnections.put(address, channel);
    }

    public SocketChannel removeInboundConnection(NetworkAddress address) {
        return inboundConnections.remove(address);
    }

    // Getters for OUTBOUND connections (our connections to replicas)
    public Map<NetworkAddress, SocketChannel> getOutboundConnections() {
        return outboundConnections;
    }

    public SocketChannel getOutboundConnection(NetworkAddress address) {
        return outboundConnections.get(address);
    }

    public void putOutboundConnection(NetworkAddress address, SocketChannel channel) {
        outboundConnections.put(address, channel);
    }

    public SocketChannel removeOutboundConnection(NetworkAddress address) {
        return outboundConnections.remove(address);
    }

    // Helper methods for PER-SOURCE inbound message queues
    public int getInboundMessageQueueSize() {
        return inboundQueues.values().stream()
                .mapToInt(BlockingQueue::size)
                .sum();
    }

    public void addInboundMessage(InboundMessage message) {
        NetworkAddress source = message.message().source();
        
        // If source is null, try to get it from the message context
        if (source == null) {
            MessageContext context = message.messageContext();
            if (context != null && context.getSourceChannel() != null) {
                // Try to extract source from the channel
                try {
                    source = NetworkAddress.from((InetSocketAddress) context.getSourceChannel().getRemoteAddress());
                } catch (Exception e) {
                    // If we can't get the source, use a default queue
                    source = new NetworkAddress("unknown", 0);
                }
            } else {
                // Fallback to a default queue for messages with no source
                source = new NetworkAddress("unknown", 0);
            }
        }
        
        inboundQueues.computeIfAbsent(source, k -> new LinkedBlockingQueue<>()).add(message);
    }

    public int drainInboundMessages(Collection<InboundMessage> collection, int maxElements) {
        int totalDrained = 0;
        int remainingElements = maxElements;
        
        // Round-robin through sources for fair processing
        for (Map.Entry<NetworkAddress, BlockingQueue<InboundMessage>> entry : inboundQueues.entrySet()) {
            if (remainingElements <= 0) break;
            
            BlockingQueue<InboundMessage> queue = entry.getValue();
            int drained = queue.drainTo(collection, remainingElements);
            totalDrained += drained;
            remainingElements -= drained;
        }
        
        return totalDrained;
    }

    // Helper methods for PER-DESTINATION outbound message queues
    public int getOutboundQueueSize() {
        return outboundQueues.values().stream()
                .mapToInt(BlockingQueue::size)
                .sum();
    }

    public void addOutboundMessage(Message message) {
        NetworkAddress destination = message.destination();
        if (destination != null) {
            outboundQueues.computeIfAbsent(destination, k -> new LinkedBlockingQueue<>()).add(message);
        }
    }

    public Message pollOutboundMessage() {
        // Round-robin through destinations for fair sending
        for (Map.Entry<NetworkAddress, BlockingQueue<Message>> entry : outboundQueues.entrySet()) {
            Message message = entry.getValue().poll();
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    public int drainOutboundMessages(Collection<Message> collection, int maxElements) {
        int totalDrained = 0;
        int remainingElements = maxElements;
        
        // Round-robin through destinations for fair sending
        for (Map.Entry<NetworkAddress, BlockingQueue<Message>> entry : outboundQueues.entrySet()) {
            if (remainingElements <= 0) break;
            
            BlockingQueue<Message> queue = entry.getValue();
            int drained = queue.drainTo(collection, remainingElements);
            totalDrained += drained;
            remainingElements -= drained;
        }
        
        return totalDrained;
    }

    // Get queue size for a specific source (for backpressure)
    public int getInboundQueueSizeForSource(NetworkAddress source) {
        BlockingQueue<InboundMessage> queue = inboundQueues.get(source);
        return queue != null ? queue.size() : 0;
    }

    // Get queue size for a specific destination
    public int getOutboundQueueSizeForDestination(NetworkAddress destination) {
        BlockingQueue<Message> queue = outboundQueues.get(destination);
        return queue != null ? queue.size() : 0;
    }

    // Get all inbound sources (for round-robin processing)
    public Set<NetworkAddress> getInboundSources() {
        return inboundQueues.keySet();
    }

    // Get all outbound destinations (for round-robin processing)
    public Set<NetworkAddress> getOutboundDestinations() {
        return outboundQueues.keySet();
    }

    // Get the inbound queues map (for direct access in processing)
    public Map<NetworkAddress, BlockingQueue<InboundMessage>> getInboundQueues() {
        return inboundQueues;
    }

    // Get the outbound queues map (for direct access in processing)
    public Map<NetworkAddress, BlockingQueue<Message>> getOutboundQueues() {
        return outboundQueues;
    }

    public int getPendingMessageCount(NetworkAddress address) {
        Queue<Message> queue = pendingMessages.get(address);
        return queue != null ? queue.size() : 0;
    }

    public void addPendingMessage(NetworkAddress address, Message message) {
        pendingMessages.computeIfAbsent(address, k -> new LinkedBlockingQueue<>()).add(message);
    }

    public Queue<Message> getOrCreatePendingMessages(NetworkAddress address) {
        return pendingMessages.computeIfAbsent(address, k -> new LinkedBlockingQueue<>());
    }

    public Set<Map.Entry<NetworkAddress, SocketChannel>> getOutboundConnectionsEntrySet() {
        return outboundConnections.entrySet();
    }

    public Map<NetworkAddress, Queue<Message>> getPendingMessages() {
        return pendingMessages;
    }

    public void clearPendingMessages(NetworkAddress address) {
        pendingMessages.remove(address);
    }

    public void clearAllPendingMessages() {
        pendingMessages.clear();
    }

    public int getTotalMessageCount() {
        int total = getOutboundQueueSize();
        for (Queue<Message> queue : pendingMessages.values()) {
            total += queue.size();
        }
        return total;
    }

    // Backpressure management
    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    public boolean shouldEnableBackpressure(int highWatermark) {
        return !backpressureEnabled && getInboundMessageQueueSize() >= highWatermark;
    }

    public boolean shouldDisableBackpressure(int lowWatermark) {
        return backpressureEnabled && getInboundMessageQueueSize() <= lowWatermark;
    }

    public void enableBackpressure() {
        this.backpressureEnabled = true;
    }

    public void disableBackpressure() {
        this.backpressureEnabled = false;
    }

    public int getCurrentInboundQueueSize() {
        return getInboundMessageQueueSize();
    }
}