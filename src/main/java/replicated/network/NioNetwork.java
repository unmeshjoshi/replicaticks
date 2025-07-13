package replicated.network;

import replicated.messaging.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO-based network implementation.
 * Uses Java NIO for non-blocking network I/O while maintaining deterministic tick() behavior.
 * <p>
 * Key features:
 * - Non-blocking I/O using NIO channels
 * - Multiple server sockets can be bound to different addresses
 * - Maintains message queues for deterministic delivery
 * - Supports network partitioning for testing
 * - Thread-safe for concurrent access
 */
public class NioNetwork implements Network {

    private final MessageCodec codec;
    private final Selector selector;

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

    // Network partitioning state
    private final Set<String> partitionedLinks = new HashSet<>();
    private final Map<String, Double> linkPacketLoss = new HashMap<>();
    private final Map<String, Integer> linkDelays = new HashMap<>();

    // Per-channel state management (replaces shared buffers to prevent data corruption)
    private final Map<SocketChannel, ChannelState> channelStates = new ConcurrentHashMap<>();

    // map message identity (object) to context
    private final Map<Message, MessageContext> messageContexts = new ConcurrentHashMap<>();

    private final Random random = new Random();

    private static final int DEFAULT_MAX_INBOUND_PER_TICK = 1000; // safeguard to avoid starving other work per tick
    private final int maxInboundPerTick;
    // === Backpressure tuning ===
    private final int backpressureHighWatermark;
    private final int backpressureLowWatermark;
    private volatile boolean backpressureEnabled = false;
    private final AtomicInteger inboundConnectionCount = new AtomicInteger();
    private final AtomicInteger outboundConnectionCount = new AtomicInteger();
    private final AtomicInteger closedConnectionCount = new AtomicInteger();

    // === Per-connection metrics ===
    public static final class ConnectionStats {
        public final NetworkAddress local;
        public final NetworkAddress remote;
        public final boolean inbound;
        private final long openedAtNanos;
        private volatile long lastActivityNanos;
        private final AtomicLong bytesSent = new AtomicLong();
        private final AtomicLong bytesReceived = new AtomicLong();
        private volatile long closedAtNanos = -1;

        ConnectionStats(NetworkAddress local, NetworkAddress remote, boolean inbound) {
            this.local = local;
            this.remote = remote;
            this.inbound = inbound;
            this.openedAtNanos = System.nanoTime();
            this.lastActivityNanos = this.openedAtNanos;
        }

        void recordSent(long n) {
            bytesSent.addAndGet(n);
            lastActivityNanos = System.nanoTime();
        }

        void recordRecv(long n) {
            bytesReceived.addAndGet(n);
            lastActivityNanos = System.nanoTime();
        }

        void markClosed() {
            closedAtNanos = System.nanoTime();
        }

        public long bytesSent() {
            return bytesSent.get();
        }

        public long bytesReceived() {
            return bytesReceived.get();
        }

        public long openedMillis() {
            return openedAtNanos / 1_000_000;
        }

        public long lastActivityMillis() {
            return lastActivityNanos / 1_000_000;
        }

        public long closedMillis() {
            return closedAtNanos < 0 ? -1 : closedAtNanos / 1_000_000;
        }

        public long lifetimeMillis() {
            long end = closedAtNanos < 0 ? System.nanoTime() : closedAtNanos;
            return (end - openedAtNanos) / 1_000_000;
        }

        @Override
        public String toString() {
            return String.format("Conn[%s -> %s, inbound=%s, sent=%d, recv=%d, openMs=%d, lifeMs=%d]", local, remote, inbound, bytesSent(), bytesReceived(), openedMillis(), lifetimeMillis());
        }
    }

    private final Map<SocketChannel, ConnectionStats> connectionStats = new ConcurrentHashMap<>();

    /**
     * Immutable snapshot of network metrics
     */
    public record Metrics(int inboundConnections, int outboundConnections, int closedConnections,
                          List<ConnectionStats> connections) {
    }

    public NioNetwork() {
        this(new JsonMessageCodec());
    }

    public NioNetwork(MessageCodec codec) {
        this(codec, DEFAULT_MAX_INBOUND_PER_TICK);
    }

    public NioNetwork(MessageCodec codec, int maxInboundPerTick) {
        this(codec, maxInboundPerTick, 10_000, 5_000);
    }

    public NioNetwork(MessageCodec codec, int maxInboundPerTick, int backpressureHighWatermark, int backpressureLowWatermark) {
        this.codec = codec;
        this.maxInboundPerTick = maxInboundPerTick;
        this.backpressureHighWatermark = backpressureHighWatermark;
        this.backpressureLowWatermark = backpressureLowWatermark;
        try {
            this.selector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create NIO selector", e);
        }
    }

    /**
     * Binds a server socket to the specified address for incoming connections.
     * This must be called before the network can receive messages at this address.
     */
    public void bind(NetworkAddress address) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }

        try {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(address.ipAddress(), address.port()));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            serverChannels.put(address, serverChannel);

        } catch (IOException e) {
            throw new RuntimeException("Failed to bind to address: " + address, e);
        }
    }

    /**
     * Unbinds the server socket from the specified address.
     */
    public void unbind(NetworkAddress address) {
        ServerSocketChannel serverChannel = serverChannels.remove(address);
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                // Log but don't fail
            }
        }
        inboundMessageQueue.remove(address);
    }

    @Override
    public void send(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        System.out.println("NIO: Sending message from " + message.source() + " to " + message.destination() +
                " type=" + message.messageType());

        // Check for network partition
        String linkKey = linkKey(message.source(), message.destination());
        if (partitionedLinks.contains(linkKey)) {
            System.out.println("NIO: Message dropped due to partition: " + linkKey);
            return; // Drop message due to partition
        }

        // Check for packet loss
        Double lossRate = linkPacketLoss.get(linkKey);
        if (lossRate != null && random.nextDouble() < lossRate) {
            System.out.println("NIO: Message dropped due to packet loss: " + linkKey);
            return; // Drop message due to packet loss
        }

        // Add to outbound queue for async processing
        outboundQueue.offer(message);
        System.out.println("NIO: Message added to outbound queue, queue size: " + outboundQueue.size());
    }


    @Override
    public void tick() {
        try {
            // Process selector events (non-blocking)
            selector.selectNow();

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();

                try {
                    if (key.isReadable()) {
                        System.out.println("NIO: Processing READ event");
                        handleRead(key);
                    } else if (key.isWritable()) {
                        System.out.println("NIO: Processing WRITE event");
                        handleWrite(key);
                    } else if (key.isConnectable()) {
                        System.out.println("NIO: Processing CONNECT event");
                        handleConnect(key);
                    } else if (key.isAcceptable()) {
                        System.out.println("NIO: Processing ACCEPT event");
                        handleAccept(key);
                    }
                } catch (IOException e) {
                    // Close problematic connection
                    key.cancel();
                    if (key.channel() != null) {
                        try {
                            key.channel().close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }

            // Process outbound messages
            processOutboundMessages();

            // Process inbound messages and deliver to callback
            processInboundMessages();

            // === Backpressure management ===
            int queueSize = inboundMessageQueue.size();
            if (!backpressureEnabled && queueSize > backpressureHighWatermark) {
                toggleReadInterest(false);
                backpressureEnabled = true;
                System.out.println("NIO: Backpressure ENABLED - queue size: " + queueSize);
            } else if (backpressureEnabled && queueSize < backpressureLowWatermark) {
                toggleReadInterest(true);
                backpressureEnabled = false;
                System.out.println("NIO: Backpressure DISABLED - queue size: " + queueSize);
            }

        } catch (IOException e) {
            throw new RuntimeException("Error in network tick", e);
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ);

            // Get the client's address from the accepted connection
            InetSocketAddress clientAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
            InetSocketAddress localAddress = (InetSocketAddress) clientChannel.getLocalAddress();

            NetworkAddress clientNetworkAddress = new NetworkAddress(
                    clientAddress.getAddress().getHostAddress(),
                    clientAddress.getPort()
            );

            NetworkAddress localNetworkAddress = new NetworkAddress(
                    localAddress.getAddress().getHostAddress(),
                    localAddress.getPort()
            );

            // Create connection info with proper metadata
            ConnectionInfo connectionInfo = new ConnectionInfo(
                    clientChannel,
                    clientNetworkAddress,
                    localNetworkAddress,
                    ConnectionInfo.ConnectionType.INBOUND
            );
            connectionInfo.setState(ConnectionInfo.ConnectionState.CONNECTED);

            // Store the accepted client channel with full metadata
            inboundConnections.put(clientChannel, connectionInfo);
            inboundConnectionCount.incrementAndGet();

            // metrics
            connectionStats.put(clientChannel, new ConnectionStats(localNetworkAddress, clientNetworkAddress, true));

            // Create channel state for the new connection
            channelStates.put(clientChannel, new ChannelState());

            System.out.println("NIO: Accepted connection from client: " + clientNetworkAddress +
                    " -> " + localNetworkAddress + " (connection info: " + connectionInfo + ")");
        }
    }

    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        System.out.println("NIO: handleConnect called for channel: " + channel);

        try {
            if (channel.finishConnect()) {
                System.out.println("NIO: Connection established successfully");
                // Connection established, switch to read mode
                key.interestOps(SelectionKey.OP_READ);

                // Find the destination address for this channel
                NetworkAddress destination = findDestinationForChannel(channel);
                if (destination != null) {
                    System.out.println("NIO: Found destination " + destination + " for connected channel");
                    
                    // Create channel state for the new outbound connection
                    channelStates.put(channel, new ChannelState());
                    
                    // Send any queued messages
                    sendQueuedMessages(destination, channel);
                    outboundConnectionCount.incrementAndGet();

                    // metrics
                    NetworkAddress localAddr = destination; // actually local? Wait we need local for outbound: source is findSourceForChannel? easier: use networkAddress mapping
                    NetworkAddress remote = destination;
                    connectionStats.put(channel, new ConnectionStats(/*local*/localAddr, remote, false));
                } else {
                    System.out.println("NIO: WARNING - Could not find destination for connected channel");
                }
            } else {
                System.out.println("NIO: Connection still pending, will retry later");
            }
        } catch (IOException e) {
            System.err.println("NIO: Connection failed: " + e.getMessage());
            key.cancel();
            channel.close();
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ChannelState channelState = channelStates.get(channel);
        if (channelState == null) {
            System.err.println("[NIO][handleRead] No ChannelState for channel " + channel);
            return;
        }
        
        ReadFrame readFrame = channelState.getReadFrame();
        int messagesDecoded = 0;
        
        try {
            while (true) {
                boolean frameComplete;
                try {
                    frameComplete = readFrame.read(channel);
                } catch (EOFException e) {
                    System.out.println("[NIO][handleRead] Channel closed by peer: " + channel);
                    cleanupConnection(channel);
                    return;
                } catch (IOException e) {
                    // Invalid header or payload length, log and reset frame, then continue
                    System.err.println("[NIO][framing] Invalid frame: " + e.getMessage() + " (resetting ReadFrame)");
                    readFrame.reset();
                    continue;
                }
                
                if (!frameComplete) {
                    // Frame is not complete, need more data
                    break;
                }
                
                // Frame is complete, process it
                ReadFrame.Frame frame = readFrame.complete();
                try {
                    // Convert ByteBuffer to byte array for decoding
                    ByteBuffer payload = frame.getPayload();
                    byte[] msgBytes = new byte[payload.remaining()];
                    payload.get(msgBytes);
                    
                    Message message = codec.decode(msgBytes, Message.class);
                    routeInboundMessage(new InboundMessage(message, new MessageContext(message, channel, getChannelSourceAddress(channel))));
                    messagesDecoded++;
                } catch (Exception e) {
                    System.err.println("[NIO][framing] Error decoding message frame: " + e.getMessage());
                    readFrame.reset();
                }
            }
        } catch (Exception e) {
            System.err.println("[NIO][handleRead] Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("[NIO][handleRead] Decoded " + messagesDecoded + " messages");
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();

        // Get channel state for this specific channel
        ChannelState channelState = channelStates.get(channel);
        if (channelState == null) {
            System.err.println("NIO: No channel state found for write operation");
            return;
        }

        // Process pending writes from the queue
        while (channelState.hasPendingWrites()) {
            WriteFrame writeFrame = channelState.getPendingWrites().peek(); // peek to keep it if partial
            if (writeFrame == null) break;
            
            int bytesWritten = writeFrame.write(channel);
            channelState.updateActivity();

            System.out.println("NIO: handleWrite - wrote " + bytesWritten + " bytes, remaining: " + writeFrame.remaining());

            if (writeFrame.isComplete()) {
                // Finished writing this frame, remove it from queue
                channelState.getPendingWrites().poll();
                System.out.println("NIO: Completed write of frame");
            } else {
                // Still have data to write, keep it in queue for next time
                System.out.println("NIO: Partial write, keeping frame in queue");
                break; // Stop processing more frames to avoid starvation
            }
            
            ConnectionStats cs = connectionStats.get(channel);
            if (cs != null) cs.recordSent(bytesWritten);
        }

        // If no more pending writes, switch back to read mode
        if (!channelState.hasPendingWrites()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            System.out.println("NIO: No more pending writes, disabled write interest");

            // Continue processing any remaining queued messages for this destination
            NetworkAddress destination = findDestinationForChannel(channel);
            if (destination != null) {
                sendQueuedMessages(destination, channel);
            }
        }
    }

    private void processOutboundMessages() {
        // Process new outbound messages
        Message message;
        int processedCount = 0;
        while ((message = outboundQueue.poll()) != null) {
            processedCount++;
            System.out.println("NIO: Processing outbound message #" + processedCount + " from " +
                    message.source() + " to " + message.destination());
            try {
                sendMessageDirectly(message);
            } catch (IOException e) {
                System.err.println("Failed to send message: " + e);
                // Message failed to send, could retry or log
            }
        }

        if (processedCount > 0) {
            System.out.println("NIO: Processed " + processedCount + " outbound messages");
        }

        // Also try to send any pending messages for established connections
        for (Map.Entry<NetworkAddress, Queue<Message>> entry : pendingMessages.entrySet()) {
            NetworkAddress destination = entry.getKey();
            Queue<Message> queue = entry.getValue();

            if (!queue.isEmpty()) {
                System.out.println("NIO: Found " + queue.size() + " pending messages for " + destination);
                SocketChannel channel = outboundConnections.get(destination);
                if (channel != null && channel.isConnected()) {
                    System.out.println("NIO: Channel connected, sending queued messages to " + destination);
                    try {
                        sendQueuedMessages(destination, channel);
                    } catch (IOException e) {
                        System.err.println("Failed to send queued messages to " + destination + ": " + e);
                    }
                } else {
                    System.out.println("NIO: Channel not connected for " + destination +
                            " (channel=" + channel + ", connected=" +
                            (channel != null ? channel.isConnected() : "null") + ")");
                }
            }
        }
    }

    /**
     * Process inbound messages from queues and deliver to registered callback.
     * This implements the push-based delivery model.
     */
    private void processInboundMessages() {
        if (messageCallback == null) {
            return; // No callback registered
        }

        // Drain up to MAX_INBOUND_PER_TICK messages from the queue in one shot to minimise contention
        List<InboundMessage> batch = new ArrayList<>();
        inboundMessageQueue.drainTo(batch, maxInboundPerTick);

        for (InboundMessage im : batch) {
            deliverInbound(im);
        }
    }

    private void toggleReadInterest(boolean enable) {
        for (SelectionKey key : selector.keys()) {
            if (key.channel() instanceof SocketChannel) {
                int ops = key.interestOps();
                if (enable) {
                    key.interestOps(ops | SelectionKey.OP_READ);
                } else {
                    key.interestOps(ops & ~SelectionKey.OP_READ);
                }
            }
        }
    }

    /**
     * Delivers a single inbound message to the registered callback, isolating
     * error handling from the hot path loop.
     */
    private void deliverInbound(InboundMessage im) {
        try {
            messageCallback.onMessage(im.message(), im.messageContext());
        } catch (Exception e) {
            System.err.println("NIO: Error in message callback: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendMessageDirectly(Message message) throws IOException {
        NetworkAddress destination = message.destination();
        SocketChannel channel = null;

        // SIMPLIFIED ROUTING: Check for inbound channel first (for responses), 
        // then fall back to outbound channel (for requests)
        channel = findInboundChannelForDestination(destination);
        if (channel != null && channel.isConnected()) {
            System.out.println("NIO: Using inbound connection for " + destination);
        } else {
            // No inbound connection, create/use outbound connection
            channel = getOrCreateClientChannel(destination);
            System.out.println("NIO: Using outbound connection for " + destination);
        }

        System.out.println("NIO: sendMessageDirectly to " + destination +
                " (channel=" + channel + ", connected=" +
                (channel != null ? channel.isConnected() : "null") + ")");

        if (channel != null && channel.isConnected()) {
            byte[] messageData = codec.encode(message);
            WriteFrame writeFrame = new WriteFrame(messageData);
            
            int bytesWritten = writeFrame.write(channel);
            System.out.println("NIO: Wrote " + bytesWritten + " bytes immediately, remaining=" + writeFrame.remaining());

            if (writeFrame.hasRemaining()) {
                // Couldn't write everything, add to pending writes queue
                ChannelState channelState = channelStates.get(channel);
                if (channelState != null) {
                    channelState.addPendingWrite(writeFrame);
                    // Register for write events if not already registered
                    SelectionKey key = channel.keyFor(selector);
                    if (key != null) {
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                        System.out.println("NIO: Registered for write events, frame queued");
                    }
                }
            } else {
                System.out.println("NIO: Message sent completely");
            }
            ConnectionStats cs = connectionStats.get(channel);
            if (cs != null) cs.recordSent(bytesWritten);
        } else {
            // Connection not ready, queue the message for later delivery
            pendingMessages.computeIfAbsent(destination, k -> new ConcurrentLinkedQueue<>()).offer(message);
            System.out.println("NIO: Message queued for later delivery, pending count for " + destination +
                    ": " + pendingMessages.get(destination).size());
        }
    }



    private SocketChannel getOrCreateClientChannel(NetworkAddress address) throws IOException {
        SocketChannel channel = outboundConnections.get(address);

        System.out.println("NIO: getOrCreateClientChannel for " + address +
                " (existing channel=" + channel +
                ", connected=" + (channel != null ? channel.isConnected() : "null") +
                ", isOpen=" + (channel != null ? channel.isOpen() : "null") + ")");

        if (channel == null || !channel.isOpen()) {
            if (channel != null) {
                System.out.println("NIO: Existing channel not open, creating new one");
            } else {
                System.out.println("NIO: No existing channel, creating new one");
            }

            channel = SocketChannel.open();
            channel.configureBlocking(false);

            boolean connected = channel.connect(new InetSocketAddress(address.ipAddress(), address.port()));
            if (!connected) {
                // Connection in progress, register for connect events
                System.out.println("NIO: Connection in progress, registering for connect events");
                channel.register(selector, SelectionKey.OP_CONNECT);
            } else {
                // Immediate connection, register for read events
                System.out.println("NIO: Immediate connection, registering for read events");
                channel.register(selector, SelectionKey.OP_READ);
            }

            outboundConnections.put(address, channel);

            // Create channel state for the new outbound connection
            channelStates.put(channel, new ChannelState());

            System.out.println("NIO: Stored new channel in outboundConnections map");
        } else {
            System.out.println("NIO: Using existing channel (connected=" + channel.isConnected() + ")");
        }

        return channel;
    }

    /**
     * Finds the NetworkAddress destination for a given SocketChannel.
     * Searches both outbound connections and inbound connections.
     */
    private NetworkAddress findDestinationForChannel(SocketChannel channel) {
        // First check outbound connections (client-side)
        for (Map.Entry<NetworkAddress, SocketChannel> entry : outboundConnections.entrySet()) {
            if (entry.getValue() == channel) {
                return entry.getKey();
            }
        }

        // Then check inbound connections (server-side) - return the remote address
        ConnectionInfo connectionInfo = inboundConnections.get(channel);
        if (connectionInfo != null) {
            return connectionInfo.getRemoteAddress();
        }

        return null;
    }

    /**
     * Finds an inbound SocketChannel for a given destination address.
     * This is used when the server needs to send responses back to a client.
     */
    private SocketChannel findInboundChannelForDestination(NetworkAddress destination) {
        for (ConnectionInfo connectionInfo : inboundConnections.values()) {
            if (connectionInfo.getRemoteAddress().equals(destination)) {
                return connectionInfo.getChannel();
            }
        }
        return null;
    }

    /**
     * Sends all queued messages for a destination once the connection is established.
     */
    private void sendQueuedMessages(NetworkAddress destination, SocketChannel channel) throws IOException {
        System.out.println("NIO: sendQueuedMessages called for " + destination);

        Queue<Message> queue = pendingMessages.get(destination);
        if (queue != null) {
            System.out.println("NIO: Found pending queue with " + queue.size() + " messages");

            Message message;
            int sentCount = 0;
            while ((message = queue.poll()) != null) {
                System.out.println("NIO: Sending queued message " + (++sentCount) + " from " +
                        message.source() + " to " + message.destination());

                byte[] messageData = codec.encode(message);
                WriteFrame writeFrame = new WriteFrame(messageData);

                int bytesWritten = writeFrame.write(channel);
                System.out.println("NIO: Wrote " + bytesWritten + " bytes, remaining=" + writeFrame.remaining());

                if (writeFrame.hasRemaining()) {
                    // Couldn't write everything, add to pending writes queue
                    ChannelState channelState = channelStates.get(channel);
                    if (channelState != null) {
                        channelState.addPendingWrite(writeFrame);
                        // Register for write events if not already registered
                        SelectionKey key = channel.keyFor(selector);
                        if (key != null) {
                            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                            System.out.println("NIO: Partial write, registered for write events");
                        }
                    }
                    break; // Stop processing more messages until this one is sent
                }
            }

            System.out.println("NIO: Sent " + sentCount + " queued messages, remaining in queue: " + queue.size());
        } else {
            System.out.println("NIO: No pending messages queue found for " + destination);
        }
    }

    @Override
    public void partition(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }

        partitionedLinks.add(linkKey(source, destination));
        partitionedLinks.add(linkKey(destination, source));
    }

    @Override
    public void partitionOneWay(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }

        partitionedLinks.add(linkKey(source, destination));
    }

    @Override
    public void healPartition(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }

        partitionedLinks.remove(linkKey(source, destination));
        partitionedLinks.remove(linkKey(destination, source));
    }

    @Override
    public void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }

        linkDelays.put(linkKey(source, destination), delayTicks);
    }

    @Override
    public void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("Loss rate must be between 0.0 and 1.0");
        }

        linkPacketLoss.put(linkKey(source, destination), lossRate);
    }

    @Override
    public NetworkAddress establishConnection(NetworkAddress destination) {
        if (destination == null) {
            throw new IllegalArgumentException("Destination address cannot be null");
        }

        try {
            // Create a new socket channel for this connection
            SocketChannel channel = SocketChannel.open();

            // Temporarily use blocking mode for connection establishment
            // This ensures we get the actual local address assigned by the OS
            channel.configureBlocking(true);

            // Connect to destination (this will block until connected)
            InetSocketAddress destinationAddress = new InetSocketAddress(
                    destination.ipAddress(), destination.port());
            boolean connected = channel.connect(destinationAddress);

            if (!connected) {
                throw new IOException("Failed to connect to " + destination);
            }

            // Get the actual local address assigned by the OS
            InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress();

            // Convert to NetworkAddress
            NetworkAddress actualClientAddress = new NetworkAddress(
                    localAddress.getAddress().getHostAddress(), localAddress.getPort());

            // Now switch back to non-blocking mode for ongoing operations
            channel.configureBlocking(false);

            // Register the channel for read events (connection is already established)
            channel.register(selector, SelectionKey.OP_READ);

            // Store the channel for future use
            outboundConnections.put(destination, channel);

            return actualClientAddress;

        } catch (IOException e) {
            throw new RuntimeException("Failed to establish connection to " + destination, e);
        }
    }

    private String linkKey(NetworkAddress source, NetworkAddress destination) {
        return source != null ? source.toString() + "->" + destination.toString()
                : "->" + destination.toString();
    }

    /**
     * Generates a unique correlation ID for request-response tracking.
     * This enables proper response routing back to the original request channel.
     */
    private String generateCorrelationId(Message message) {
        return message.source() + "->" + message.destination() + "-" +
                message.messageType() + "-" + System.currentTimeMillis() + "-" +
                random.nextInt(1000);
    }

    /**
     * Comprehensive connection cleanup to prevent resource leaks.
     * This follows production patterns for proper connection lifecycle management.
     */
    private void cleanupConnection(SocketChannel channel) {
        try {
            // Cancel any selection key
            SelectionKey key = channel.keyFor(selector);
            if (key != null) key.cancel();

            channelStates.remove(channel);

            ConnectionStats cs = connectionStats.remove(channel);
            if (cs != null) cs.markClosed();

            // Determine whether it was inbound or outbound
            if (inboundConnections.remove(channel) != null) {
                inboundConnectionCount.decrementAndGet();
            } else {
                // Remove from outbound map (by value)
                outboundConnections.entrySet().removeIf(e -> {
                    if (e.getValue().equals(channel)) {
                        outboundConnectionCount.decrementAndGet();
                        return true;
                    }
                    return false;
                });
            }

            closedConnectionCount.incrementAndGet();

            channel.close();
            System.out.println("NIO: Connection cleanup completed for channel");

        } catch (IOException e) {
            System.err.println("NIO: Error during connection cleanup: " + e.getMessage());
        }
    }

    /**
     * Gets the source address for a channel based on connection type.
     */
    private NetworkAddress getChannelSourceAddress(SocketChannel channel) {
        // Check if it's an inbound connection
        ConnectionInfo connectionInfo = inboundConnections.get(channel);
        if (connectionInfo != null) {
            return connectionInfo.getRemoteAddress();
        }

        // Check if it's an outbound connection
        for (Map.Entry<NetworkAddress, SocketChannel> entry : outboundConnections.entrySet()) {
            if (entry.getValue() == channel) {
                try {
                    InetSocketAddress remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
                    return new NetworkAddress(remoteAddress.getAddress().getHostAddress(), remoteAddress.getPort());
                } catch (IOException e) {
                    return entry.getKey(); // Fallback to destination address
                }
            }
        }

        return null;
    }

    /**
     * Routes inbound messages with preserved context information.
     * This enables proper response routing back to the source channel.
     */
    private void routeInboundMessage(InboundMessage im) {
        Message message = im.message();
        MessageContext messageContext = im.messageContext();

        System.out.println("NIO: routeInboundMessage called for " + message.messageType() + " from " +
                message.source() + " to " + message.destination());

        try {
            // Store request context for response correlation
            if (messageContext.isRequest()) {
                System.out.println("NIO: Message is a request, storing context");
                String correlationId = generateCorrelationId(message);
                messageContext.setCorrelationId(correlationId);
                System.out.println("NIO: Stored request context for correlation: " + correlationId +
                        ", context: " + messageContext);
            } else {
                System.out.println("NIO: Message is not a request, skipping context storage");
            }

            System.out.println("NIO: Creating queue for destination: " + message.destination());
            inboundMessageQueue.offer(im);
            System.out.println("NIO: Message added to receive queue for " + message.destination() +
                    ", queue size: " + inboundMessageQueue.size() + ", context: " + messageContext);
        } catch (Exception e) {
            System.err.println("NIO: Exception in routeInboundMessage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public MessageContext getContextFor(Message message) {
        return messageContexts.get(message);
    }

    /**
     * Closes all network resources.
     */
    public void close() {
        try {
            // Close all outbound connections
            for (SocketChannel channel : outboundConnections.values()) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }

            // Close all inbound connections
            for (ConnectionInfo connectionInfo : inboundConnections.values()) {
                try {
                    connectionInfo.getChannel().close();
                } catch (IOException ignored) {
                }
            }

            // Clean up all channel states
            for (ChannelState state : channelStates.values()) {
                state.cleanup();
            }
            channelStates.clear();

            // Close all server channels
            for (ServerSocketChannel channel : serverChannels.values()) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }

            // Close selector
            selector.close();

        } catch (IOException e) {
            throw new RuntimeException("Error closing network resources", e);
        }
    }

    // === NEW PUBLIC API =========================================================
    @Override
    public void sendOnChannel(SocketChannel channel, Message message) {
        if (channel == null || message == null) {
            throw new IllegalArgumentException("Channel and message must not be null");
        }
        if (!channel.isOpen()) {
            throw new IllegalStateException("Channel is closed: " + channel);
        }
        byte[] messageData = codec.encode(message);
        WriteFrame writeFrame = new WriteFrame(messageData);
        ChannelState state = channelStates.computeIfAbsent(channel, c -> new ChannelState());
        state.addPendingWrite(writeFrame);

        // Ensure OP_WRITE interest so selector wakes up next tick
        SelectionKey key = channel.keyFor(selector);
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    /**
     * Returns a snapshot of current connection metrics.
     */
    public Metrics getMetrics() {
        return new Metrics(inboundConnectionCount.get(), outboundConnectionCount.get(), closedConnectionCount.get(),
                List.copyOf(connectionStats.values()));
    }

    private MessageCallback messageCallback;

    @Override
    public void registerMessageHandler(MessageCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("MessageCallback cannot be null");
        }
        this.messageCallback = callback;
    }

    // Package-private for test visibility
    boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    private record InboundMessage(Message message, MessageContext messageContext) {
    }
}