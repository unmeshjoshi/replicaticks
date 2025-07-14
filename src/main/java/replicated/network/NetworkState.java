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
    
    // Accepted client connections with full metadata (inbound connections from clients to us as server)
    private final Map<SocketChannel, ConnectionInfo> inboundConnections = new ConcurrentHashMap<>();
    
    // Inbound message queue
    private final BlockingQueue<InboundMessage> inboundMessageQueue = new LinkedBlockingQueue<>();
    
    // Outbound message queue for async sending
    private final Queue<Message> outboundQueue = new LinkedBlockingQueue<>();
    
    // Queue for messages pending connection establishment
    private final Map<NetworkAddress, Queue<Message>> pendingMessages = new ConcurrentHashMap<>();
    
    // map message identity (object) to context
    private final Map<Message, MessageContext> messageContexts = new ConcurrentHashMap<>();

    // Backpressure management
    private volatile boolean backpressureEnabled = false;

    // Getters for server channels
    public Map<NetworkAddress, ServerSocketChannel> getServerChannels() {
        return serverChannels;
    }

    public ServerSocketChannel getServerChannel(NetworkAddress address) {
        return serverChannels.get(address);
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

    public SocketChannel removeOutboundConnection(NetworkAddress address) {
        return outboundConnections.remove(address);
    }

    // Getters for inbound connections
    public Map<SocketChannel, ConnectionInfo> getInboundConnections() {
        return inboundConnections;
    }

    public ConnectionInfo getInboundConnection(SocketChannel channel) {
        return inboundConnections.get(channel);
    }

    public void putInboundConnection(SocketChannel channel, ConnectionInfo info) {
        inboundConnections.put(channel, info);
    }

    public ConnectionInfo removeInboundConnection(SocketChannel channel) {
        return inboundConnections.remove(channel);
    }

    // Getters for message queues
    public BlockingQueue<InboundMessage> getInboundMessageQueue() {
        return inboundMessageQueue;
    }

    public Queue<Message> getOutboundQueue() {
        return outboundQueue;
    }

    public Map<NetworkAddress, Queue<Message>> getPendingMessages() {
        return pendingMessages;
    }

    public Queue<Message> getPendingMessages(NetworkAddress address) {
        return pendingMessages.get(address);
    }

    public void putPendingMessages(NetworkAddress address, Queue<Message> messages) {
        pendingMessages.put(address, messages);
    }

    public Queue<Message> removePendingMessages(NetworkAddress address) {
        return pendingMessages.remove(address);
    }

    // Getters for message contexts
    public Map<Message, MessageContext> getMessageContexts() {
        return messageContexts;
    }

    public MessageContext getMessageContext(Message message) {
        return messageContexts.get(message);
    }

    public void putMessageContext(Message message, MessageContext context) {
        messageContexts.put(message, context);
    }

    public MessageContext removeMessageContext(Message message) {
        return messageContexts.remove(message);
    }

    // Helper methods for inbound message queue
    public int getInboundMessageQueueSize() {
        return inboundMessageQueue.size();
    }

    public void addInboundMessage(InboundMessage message) {
        inboundMessageQueue.add(message);
    }

    public InboundMessage pollInboundMessage() {
        return inboundMessageQueue.poll();
    }

    public boolean hasInboundMessages() {
        return !inboundMessageQueue.isEmpty();
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

    public boolean hasOutboundMessages() {
        return !outboundQueue.isEmpty();
    }

    // Helper methods for pending messages
    public boolean hasPendingMessages(NetworkAddress address) {
        Queue<Message> queue = pendingMessages.get(address);
        return queue != null && !queue.isEmpty();
    }

    public int getPendingMessageCount(NetworkAddress address) {
        Queue<Message> queue = pendingMessages.get(address);
        return queue != null ? queue.size() : 0;
    }

    public void addPendingMessage(NetworkAddress address, Message message) {
        pendingMessages.computeIfAbsent(address, k -> new LinkedBlockingQueue<>()).add(message);
    }

    public Message pollPendingMessage(NetworkAddress address) {
        Queue<Message> queue = pendingMessages.get(address);
        return queue != null ? queue.poll() : null;
    }

    public Queue<Message> getOrCreatePendingMessages(NetworkAddress address) {
        return pendingMessages.computeIfAbsent(address, k -> new LinkedBlockingQueue<>());
    }

    // Helper methods for connections
    public boolean hasOutboundConnection(NetworkAddress address) {
        return outboundConnections.containsKey(address);
    }

    public boolean hasInboundConnection(SocketChannel channel) {
        return inboundConnections.containsKey(channel);
    }

    public int getOutboundConnectionCount() {
        return outboundConnections.size();
    }

    public Set<Map.Entry<NetworkAddress, SocketChannel>> getOutboundConnectionsEntrySet() {
        return outboundConnections.entrySet();
    }

    public int getInboundConnectionCount() {
        return inboundConnections.size();
    }

    public boolean hasServerChannel(NetworkAddress address) {
        return serverChannels.containsKey(address);
    }

    public int getServerChannelCount() {
        return serverChannels.size();
    }

    // Helper methods for message contexts
    public boolean hasMessageContext(Message message) {
        return messageContexts.containsKey(message);
    }

    public int getMessageContextCount() {
        return messageContexts.size();
    }

    // Clear methods
    public void clearInboundMessageQueue() {
        inboundMessageQueue.clear();
    }

    public void clearOutboundQueue() {
        outboundQueue.clear();
    }

    public void clearPendingMessages(NetworkAddress address) {
        pendingMessages.remove(address);
    }

    public void clearAllPendingMessages() {
        pendingMessages.clear();
    }

    public void clearMessageContexts() {
        messageContexts.clear();
    }

    // Utility methods
    public boolean hasAnyConnections() {
        return !outboundConnections.isEmpty() || !inboundConnections.isEmpty();
    }

    public boolean hasAnyMessages() {
        return !inboundMessageQueue.isEmpty() || !outboundQueue.isEmpty() || !pendingMessages.isEmpty();
    }

    public boolean hasAnyServerChannels() {
        return !serverChannels.isEmpty();
    }

    public int getTotalConnectionCount() {
        return outboundConnections.size() + inboundConnections.size();
    }

    public int getTotalMessageCount() {
        int total = inboundMessageQueue.size() + outboundQueue.size();
        for (Queue<Message> queue : pendingMessages.values()) {
            total += queue.size();
        }
        return total;
    }

    // Backpressure management
    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    public void setBackpressureEnabled(boolean enabled) {
        this.backpressureEnabled = enabled;
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