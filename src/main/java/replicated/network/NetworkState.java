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
import replicated.messaging.NetworkAddress;

/**
 * Manages the state of network connections and message queues.
 * Thread-safe state container for NioNetwork.
 */
public final class NetworkState {

    // Server sockets bound to specific addresses
    private final Map<NetworkAddress, ServerSocketChannel> serverChannels = new ConcurrentHashMap<>();

    // Client connections to other nodes (outbound connections we initiate)
    private final Map<NetworkAddress, SocketChannel> outboundConnections = new ConcurrentHashMap<>();

    // Inbound message queue
    private final BlockingQueue<InboundMessage> inboundMessageQueue = new LinkedBlockingQueue<>();

    // Outbound message queue for async sending
    private final BlockingQueue<Message> outboundQueue = new LinkedBlockingQueue<>();

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

    // Getters for outbound connections
    public Map<NetworkAddress, SocketChannel> getOutboundConnections() {
        return outboundConnections;
    }

    public SocketChannel getOutboundConnection(NetworkAddress address) {
        return outboundConnections.get(address);
    }

    public void putOutboundConnection(NetworkAddress address, SocketChannel channel) {
        outboundConnections.put(address, channel);
    }

    // Helper methods for inbound message queue
    public int getInboundMessageQueueSize() {
        return inboundMessageQueue.size();
    }

    public void addInboundMessage(InboundMessage message) {
        inboundMessageQueue.add(message);
    }

    public int drainInboundMessages(Collection<InboundMessage> collection, int maxElements) {
        return inboundMessageQueue.drainTo(collection, maxElements);
    }

    // Helper methods for outbound message queue
    public int getOutboundQueueSize() {
        return outboundQueue.size();
    }

    public void addOutboundMessage(Message message) {
        outboundQueue.add(message);
    }

    public Message pollOutboundMessage() {
        return outboundQueue.poll();
    }

    public int drainOutboundMessages(Collection<Message> collection, int maxElements) {
        return outboundQueue.drainTo(collection, maxElements);
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
        int total = outboundQueue.size();
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
        return inboundMessageQueue.size();
    }
}