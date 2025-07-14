package replicated.network;

import replicated.messaging.JsonMessageCodec;
import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.stream.Collectors;

/**
 * NIO-based network implementation.
 * Uses Java NIO for non-blocking network I/O while maintaining tick() behavior.
 * <p>
 * Key features:
 * - Non-blocking I/O using NIO channels
 * - Multiple server sockets can be bound to different addresses
 * - Maintains message queues for delivery
 * - Supports network partitioning for testing
 * - Thread-safe for concurrent access
 */
public class NioNetwork implements Network {

    private final NetworkConfig config;
    private final NetworkFaultConfig faultConfig;
    //Simple collector of network metrics.
    private final MetricsCollector metricsCollector = new MetricsCollector();


    private final MessageCodec codec;
    private final Selector selector;
    // Network state management

    private final NetworkState state = new NetworkState();

    private final Random random = new Random();


    public NioNetwork() {
        this(new JsonMessageCodec(), NetworkConfig.defaults(), new NetworkFaultConfig(Set.of(), Map.of(), Map.of()));
    }

    public NioNetwork(MessageCodec codec) {
        this(codec, NetworkConfig.defaults(), new NetworkFaultConfig(Set.of(), Map.of(), Map.of()));
    }

    public NioNetwork(MessageCodec codec, int maxInboundPerTick) {
        this(codec, NetworkConfig.builder().maxInboundPerTick(maxInboundPerTick).build(), new NetworkFaultConfig(Set.of(), Map.of(), Map.of()));
    }

    public NioNetwork(MessageCodec codec, int maxInboundPerTick, int backpressureHighWatermark, int backpressureLowWatermark) {
        this(codec, NetworkConfig.builder()
                .maxInboundPerTick(maxInboundPerTick)
                .backpressureHighWatermark(backpressureHighWatermark)
                .backpressureLowWatermark(backpressureLowWatermark)
                .build(), new NetworkFaultConfig(Set.of(), Map.of(), Map.of()));
    }

    public NioNetwork(MessageCodec codec, NetworkConfig config) {
        this(codec, config, new NetworkFaultConfig(Set.of(), Map.of(), Map.of()));
    }

    public NioNetwork(MessageCodec codec, NetworkConfig config, NetworkFaultConfig faultConfig) {
        this.codec = codec;
        this.config = config;
        this.faultConfig = faultConfig;
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
            state.putServerChannel(address, serverChannel);

        } catch (IOException e) {
            throw new RuntimeException("Failed to bind to address: " + address, e);
        }
    }

    /**
     * Unbinds the server socket from the specified address.
     */
    public void unbind(NetworkAddress address) {
        ServerSocketChannel serverChannel = state.removeServerChannel(address);
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                // Log but don't fail
            }
        }
        // Note: This method needs to be updated to handle address-based removal from inbound queue
    }

    @Override
    public void send(Message message) {
        validate(message);

        System.out.println("NIO: Sending message from " + message.source() + " to " + message.destination() +
                " type=" + message.messageType());

        if (deliverIfSelf(message)) {
            return;
        }

        if (shouldDrop(message)) { // Drop the message if packet loss or link partition configured.
            return;
        }

        // Add to outbound queue for async processing
        state.addOutboundMessage(message);
        System.out.println("NIO: Message added to outbound queue, queue size: " + state.getOutboundQueueSize());
    }

    private boolean deliverIfSelf(Message message) {
        // Check for self-message (source == destination)
        if (isSelfMessage(message)) {
            System.out.println("NIO: Self-message detected, delivering directly to local handler");
            deliverSelfMessage(message);
            return true;
        }
        return false;
    }

    private static boolean isSelfMessage(Message message) {
        return message.source() != null && message.source().equals(message.destination());
    }

    private static void validate(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
    }

    private boolean shouldDrop(Message message) {
        // Check for network partition
        String linkKey = linkKey(message.source(), message.destination());
        if (faultConfig.getPartitionedLinks().contains(linkKey)) {
            System.out.println("NIO: Message dropped due to partition: " + linkKey);
            return true;
        }

        // Check for packet loss
        Double lossRate = faultConfig.getLinkPacketLoss().get(linkKey);
        if (lossRate != null && random.nextDouble() < lossRate) {
            System.out.println("NIO: Message dropped due to packet loss: " + linkKey);
            return true;
        }
        return false;
    }


    @Override
    public void tick() {
        try {
            // Check if selector is still open before using it
            if (!selector.isOpen()) {
                System.err.println("NIO: Selector is closed, skipping tick");
                return;
            }
            
            // Process selector events (non-blocking)
            selector.selectNow();

            processSelectedKeys(selector.selectedKeys());

            // Process outbound messages
            processOutboundMessages();

            // Process inbound messages and deliver to callback
            processInboundMessages();

            // === Backpressure management ===
            if (state.shouldEnableBackpressure(config.backpressureHighWatermark())) {
                toggleReadInterest(false);
                state.enableBackpressure();
                System.out.println("NIO: Backpressure ENABLED - queue size: " + state.getCurrentInboundQueueSize());
            } else if (state.shouldDisableBackpressure(config.backpressureLowWatermark())) {
                toggleReadInterest(true);
                state.disableBackpressure();
                System.out.println("NIO: Backpressure DISABLED - queue size: " + state.getCurrentInboundQueueSize());
            }

        } catch (IOException e) {
            throw new RuntimeException("Error in network tick", e);
        }
    }

    private void processSelectedKeys(Set<SelectionKey> selectedKeys) {
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
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel != null) {
            try {
                clientChannel.configureBlocking(false);
                SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);

                // Get the client's address from the accepted connection
                InetSocketAddress clientAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
                InetSocketAddress localAddress = (InetSocketAddress) clientChannel.getLocalAddress();

                // Validate addresses are not null
                if (clientAddress == null || localAddress == null) {
                    System.err.println("NIO: WARNING - Received connection with null addresses, closing channel");
                    clientKey.cancel();
                    clientChannel.close();
                    return;
                }

                NetworkAddress clientNetworkAddress = new NetworkAddress(
                        clientAddress.getAddress().getHostAddress(),
                        clientAddress.getPort()
                );

                NetworkAddress localNetworkAddress = new NetworkAddress(
                        localAddress.getAddress().getHostAddress(),
                        localAddress.getPort()
                );

                // Create channel state for the new inbound connection and attach to SelectionKey
                ChannelState channelState = ChannelState.forInbound(clientNetworkAddress);
                clientKey.attach(channelState);

                // Track metrics for the new inbound connection
                metricsCollector.incrementInboundConnection();
                metricsCollector.registerConnection(clientChannel.toString(), new ConnectionStats(localNetworkAddress, clientNetworkAddress, true));

                System.out.println("NIO: Accepted connection from client: " + clientNetworkAddress +
                        " -> " + localNetworkAddress);

            } catch (Exception e) {
                // Clean up resources if anything goes wrong
                System.err.println("NIO: Error setting up accepted connection: " + e.getMessage());
                try {
                    SelectionKey clientKey = clientChannel.keyFor(selector);
                    if (clientKey != null) {
                        clientKey.cancel();
                    }
                    clientChannel.close();
                } catch (IOException cleanupException) {
                    System.err.println("NIO: Error during cleanup: " + cleanupException.getMessage());
                }
                throw e; // Re-throw to let caller handle it
            }
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

                // Get the destination from the ChannelState that was attached during connection creation
                ChannelState channelState = (ChannelState) key.attachment();
                if (channelState != null && channelState.isOutbound()) {
                    NetworkAddress destination = channelState.getRemoteAddress();
                    System.out.println("NIO: Found destination " + destination + " for connected channel");

                    // Send any queued messages
                    sendQueuedMessages(destination, channel);
                    metricsCollector.incrementOutboundConnection();

                    // Use the outbound-specific constructor for metrics
                    ConnectionStats stats = ConnectionStats.forOutbound(destination);
                    metricsCollector.registerConnection(channel.toString(), stats);
                } else {
                    System.out.println("NIO: WARNING - No valid ChannelState attached to SelectionKey for connected channel");
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
        ChannelState channelState = (ChannelState) key.attachment();
        if (channelState == null) {
            System.err.println("[NIO][handleRead] No ChannelState attached to SelectionKey for channel " + channel);
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

        // Get channel state from SelectionKey attachment
        ChannelState channelState = (ChannelState) key.attachment();
        if (channelState == null) {
            System.err.println("NIO: No channel state attached to SelectionKey for write operation");
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

            metricsCollector.recordSent(channel.toString(), bytesWritten);
        }

        // If no more pending writes, switch back to read mode
        if (!channelState.hasPendingWrites()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            System.out.println("NIO: No more pending writes, disabled write interest");
        }
    }

    private void processOutboundMessages() {
        // Process new outbound messages
        Message message;
        int processedCount = 0;
        while ((message = state.pollOutboundMessage()) != null) {
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
        if (!state.getPendingMessages().isEmpty()) {
            for (Map.Entry<NetworkAddress, Queue<Message>> entry : state.getPendingMessages().entrySet()) {
                NetworkAddress destination = entry.getKey();
                Queue<Message> queue = entry.getValue();

                if (!queue.isEmpty()) {
                    // Log the message types that are pending
                    String messageTypes = queue.stream()
                            .map(msg -> msg.messageType().toString())
                            .distinct()
                            .collect(Collectors.joining(", "));
                    System.out.println("NIO: Found " + state.getPendingMessageCount(destination) + " pending messages for " + destination + " (types: " + messageTypes + ")");
                    SocketChannel channel = state.getOutboundConnection(destination);
                    if (channel != null && channel.isConnected()) {
                        System.out.println("NIO: Channel connected, sending queued messages to " + destination);
                        try {
                            sendQueuedMessages(destination, channel);
                        } catch (IOException e) {
                            System.err.println("Failed to send queued messages to " + destination + ": " + e);
                        }
                    } else if (channel != null && channel.isOpen()) {
                        // Channel exists but not yet connected - this is normal during connection establishment
                        // Don't clear pending messages, they'll be sent once connection completes
                        System.out.println("NIO: Channel exists but not yet connected for " + destination +
                                " (channel=" + channel + ", connected=" + channel.isConnected() +
                                ", open=" + channel.isOpen() + ") - keeping pending messages");
                    } else {
                        System.out.println("NIO: No channel for " + destination +
                                " (channel=" + channel + ")");

                        // Check if these are internal response messages (replica-to-replica)
                        // If so, don't clear them as they're legitimate pending responses
                        boolean hasInternalResponses = queue.stream()
                                .anyMatch(msg -> msg.messageType().toString().contains("INTERNAL_") &&
                                        msg.messageType().toString().contains("RESPONSE"));

                        if (hasInternalResponses) {
                            System.out.println("NIO: Keeping pending internal responses for " + destination + " (replica communication)");
                        } else {
                            System.out.println("NIO: Clearing pending messages for disconnected destination " + destination);
                            state.clearPendingMessages(destination);
                        }
                    }
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

        // Drain up to maxInboundPerTick messages from the queue in one shot to minimise contention
        List<InboundMessage> batch = new ArrayList<>();
        state.drainInboundMessages(batch, config.maxInboundPerTick());

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

    /**
     * Delivers a self-message directly to the local message handler without going through the network.
     * This optimizes replica-to-self communication by bypassing the network layer entirely.
     */
    private void deliverSelfMessage(Message message) {
        if (messageCallback == null) {
            System.err.println("NIO: No message callback registered for self-message delivery");
            return;
        }

        try {
            // Create a message context for the self-message
            MessageContext context = new MessageContext(message);

            // Deliver directly to the message handler
            messageCallback.onMessage(message, context);
            System.out.println("NIO: Self-message delivered successfully");
        } catch (Exception e) {
            System.err.println("NIO: Error delivering self-message: " + e.getMessage());
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
            if (message.messageType().isRequest()) {
                channel = getOrCreateClientChannel(destination);
                System.out.println("NIO: Using outbound connection for " + destination);
            }

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
                SelectionKey key = channel.keyFor(selector);
                if (key != null) {
                    ChannelState channelState = (ChannelState) key.attachment();
                    if (channelState != null) {
                        channelState.addPendingWrite(writeFrame);
                        // Register for write events if not already registered
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                        System.out.println("NIO: Registered for write events, frame queued");
                    }
                }
            } else {
                System.out.println("NIO: Message sent completely");
            }
            metricsCollector.recordSent(channel.toString(), bytesWritten);
        } else {
            // Connection not ready, queue the message for later delivery
            state.addPendingMessage(destination, message);
            System.out.println("NIO: Message queued for later delivery, pending count for " + destination +
                    ": " + state.getPendingMessageCount(destination));
        }
    }


    private SocketChannel getOrCreateClientChannel(NetworkAddress address) throws IOException {
        SocketChannel channel = state.getOutboundConnection(address);

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
            SelectionKey key;
            if (!connected) {
                // Connection in progress, register for connect events
                System.out.println("NIO: Connection in progress, registering for connect events");
                key = channel.register(selector, SelectionKey.OP_CONNECT);
            } else {
                // Immediate connection, register for read events
                System.out.println("NIO: Immediate connection, registering for read events");
                key = channel.register(selector, SelectionKey.OP_READ);
            }

            state.putOutboundConnection(address, channel);

            // Create channel state for the new outbound connection and attach to SelectionKey
            ChannelState channelState = ChannelState.forOutbound(address);
            key.attach(channelState);

            System.out.println("NIO: Stored new channel in outboundConnections map");
        } else {
            System.out.println("NIO: Using existing channel (connected=" + channel.isConnected() + ")");
        }

        return channel;
    }

    /**
     * Finds an inbound channel that can be used to send messages to a specific destination.
     * This is used when the server needs to send responses back to a client.
     */
    private SocketChannel findInboundChannelForDestination(NetworkAddress destination) {
        // Iterate over all SelectionKeys to find inbound channels
        for (SelectionKey key : selector.keys()) {
            if (key.channel() instanceof SocketChannel) {
                SocketChannel channel = (SocketChannel) key.channel();
                ChannelState channelState = (ChannelState) key.attachment();

                if (channelState != null && channelState.isInbound() &&
                        channelState.getRemoteAddress().equals(destination)) {
                    return channel;
                }
            }
        }
        return null;
    }

    /**
     * Sends all queued messages for a destination once the connection is established.
     */
    private void sendQueuedMessages(NetworkAddress destination, SocketChannel channel) throws IOException {
        System.out.println("NIO: sendQueuedMessages called for " + destination);

        Queue<Message> queue = state.getOrCreatePendingMessages(destination);
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
                    SelectionKey key = channel.keyFor(selector);
                    if (key != null) {
                        ChannelState channelState = (ChannelState) key.attachment();
                        if (channelState != null) {
                            channelState.addPendingWrite(writeFrame);
                            // Register for write events if not already registered
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
        faultConfig.addPartitionedLink(linkKey(source, destination));
        faultConfig.addPartitionedLink(linkKey(destination, source));
    }

    @Override
    public void partitionOneWay(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        faultConfig.addPartitionedLink(linkKey(source, destination));
    }

    @Override
    public void healPartition(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        faultConfig.removePartitionedLink(linkKey(source, destination));
        faultConfig.removePartitionedLink(linkKey(destination, source));
    }

    @Override
    public void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }
        faultConfig.setLinkDelay(linkKey(source, destination), delayTicks);
    }

    @Override
    public void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("Loss rate must be between 0.0 and 1.0");
        }
        faultConfig.setLinkPacketLoss(linkKey(source, destination), lossRate);
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
            SelectionKey key = channel.register(selector, SelectionKey.OP_READ);

            // Create channel state for the new outbound connection and attach to SelectionKey
            ChannelState channelState = ChannelState.forOutbound(destination);
            key.attach(channelState);

            // Store the channel for future use
            state.getOutboundConnections().put(destination, channel);

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
            // Cancel any selection key (this will also detach the ChannelState)
            SelectionKey key = channel.keyFor(selector);
            if (key != null) {
                ChannelState channelState = (ChannelState) key.attachment();
                key.attach(null); // Clear the attachment
                key.cancel();

                // Determine whether it was inbound or outbound based on ChannelState
                if (channelState != null && channelState.isInbound()) {
                    // Inbound connection closed - no need to decrement counter as it's cumulative
                    System.out.println("NIO: Inbound connection closed");
                } else {
                    // Outbound connection closed - clear pending messages
                    NetworkAddress destination = channelState != null ? channelState.getRemoteAddress() : null;
                    if (destination != null) {
                        state.clearPendingMessages(destination);
                        System.out.println("NIO: Cleared pending messages for closed connection to " + destination);
                    }
                }
            }

            metricsCollector.markConnectionClosed(channel.toString());
            metricsCollector.unregisterConnection(channel.toString());
            metricsCollector.incrementClosedConnection();

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
        // Get ChannelState from SelectionKey attachment
        SelectionKey key = channel.keyFor(selector);
        if (key != null) {
            ChannelState channelState = (ChannelState) key.attachment();
            if (channelState != null) {
                return channelState.getRemoteAddress();
            }
        }

        // Fallback: try to get remote address directly from channel
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
            if (remoteAddress != null) {
                return new NetworkAddress(remoteAddress.getAddress().getHostAddress(), remoteAddress.getPort());
            }
        } catch (IOException e) {
            // Ignore and return null
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
            state.addInboundMessage(im);
            System.out.println("NIO: Message added to receive queue for " + message.destination() +
                    ", queue size: " + state.getInboundMessageQueueSize() + ", context: " + messageContext);
        } catch (Exception e) {
            System.err.println("NIO: Exception in routeInboundMessage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public MessageContext getContextFor(Message message) {
        return state.getMessageContexts().get(message);
    }

    /**
     * Closes all network resources.
     */
    public void close() {
        try {
            System.out.println("NIO: Starting network shutdown...");

            clearAllPendingMessages();

            if (selector.isOpen()){
                // Close all inbound connections
                for (SelectionKey key : selector.keys()) {
                    if (key.channel() instanceof SocketChannel) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        try {
                            channel.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }

            // Close all server channels
            for (ServerSocketChannel channel : state.getServerChannels().values()) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }

            // Close selector (must be last)
            selector.close();

            System.out.println("NIO: Network shutdown completed");

        } catch (IOException e) {
            throw new RuntimeException("Error closing network resources", e);
        }
    }

    private void clearAllPendingMessages() {
        // Clear all pending messages first
        int pendingCount = state.getTotalMessageCount();
        if (pendingCount > 0) {
            System.out.println("NIO: Clearing " + pendingCount + " pending messages during shutdown");

            // Log what message types are being cleared
            for (Map.Entry<NetworkAddress, Queue<Message>> entry : state.getPendingMessages().entrySet()) {
                NetworkAddress destination = entry.getKey();
                Queue<Message> queue = entry.getValue();
                if (!queue.isEmpty()) {
                    String messageTypes = queue.stream()
                            .map(msg -> msg.messageType().toString())
                            .distinct()
                            .collect(Collectors.joining(", "));
                    System.out.println("NIO: Clearing pending messages for " + destination + " (types: " + messageTypes + ")");
                }
            }

            state.clearAllPendingMessages();
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

        // Get channel state from SelectionKey attachment
        SelectionKey key = channel.keyFor(selector);
        if (key != null) {
            ChannelState channelState = (ChannelState) key.attachment();
            if (channelState != null) {
                channelState.addPendingWrite(writeFrame);
            } else {
                // Create a default channel state if none exists
                channelState = new ChannelState();
                key.attach(channelState);
                channelState.addPendingWrite(writeFrame);
            }
        }

        // Ensure OP_WRITE interest so selector wakes up next tick
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    /**
     * Returns a snapshot of current connection metrics.
     */
    public Metrics getMetrics() {
        return metricsCollector.snapshot();
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
        return state.isBackpressureEnabled();
    }

    /**
     * Gets the client channel for a specific destination address.
     * This is primarily used for testing purposes.
     *
     * @param destination the destination address to look up
     * @return the SocketChannel for the destination, or null if no connection exists
     */
    public SocketChannel getClientChannel(NetworkAddress destination) {
        return state.getOutboundConnections().get(destination);
    }
}