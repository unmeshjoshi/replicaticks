package replicated.network;

import replicated.messaging.JsonMessageCodec;
import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.Spliterator;
import java.util.Spliterators;

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
    private final Logger logger = Logger.getLogger(NioNetwork.class.getName());


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
                setupInboundConnection(clientChannel);
            } catch (Exception e) {
                handleInboundConnectionSetupFailure(clientChannel, e);
                throw e;
            }
        }
    }

    private void setupInboundConnection(SocketChannel clientChannel) throws IOException {
        configureNonBlocking(clientChannel);
        SelectionKey clientKey = registerChannelForRead(clientChannel);
        NetworkAddress clientAddress = extractClientAddress(clientChannel);
        //clientAddress can never be null for inbound connections.
        attachChannelState(clientKey, clientAddress);
        metricsCollector.registerInboundConnection(clientChannel.toString(), clientChannel, clientAddress);

        logSuccessfulConnection(clientAddress);
    }

    private NetworkAddress extractClientAddress(SocketChannel clientChannel) throws IOException {
        InetSocketAddress clientAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
        return NetworkAddress.from(clientAddress);
    }

    private void configureNonBlocking(SocketChannel channel) throws IOException {
        channel.configureBlocking(false);
    }

    private SelectionKey registerChannelForRead(SocketChannel channel) throws IOException {
        return channel.register(selector, SelectionKey.OP_READ);
    }

    private void attachChannelState(SelectionKey clientKey, NetworkAddress clientAddress) {
        ChannelState channelState = ChannelState.forInbound(clientAddress);
        clientKey.attach(channelState);
    }

    private void logSuccessfulConnection(NetworkAddress clientAddress) {
        System.out.println("NIO: Accepted connection from client: " + clientAddress);
    }

    private void handleInboundConnectionSetupFailure(SocketChannel clientChannel, Exception e) {
        System.err.println("NIO: Error setting up accepted connection: " + e.getMessage());
        cleanupFailedInboundConnection(clientChannel);
    }

    private void cleanupFailedInboundConnection(SocketChannel clientChannel) {
        try {
            SelectionKey clientKey = clientChannel.keyFor(selector);
            if (clientKey != null) {
                clientKey.cancel();
            }
            clientChannel.close();
        } catch (IOException cleanupException) {
            System.err.println("NIO: Error during cleanup: " + cleanupException.getMessage());
        }
    }


    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        System.out.println("NIO: handleConnect called for channel: " + channel);
        try {
            if (!channel.finishConnect()) {
                System.out.println("NIO: Connection still pending, will retry later");
            }

            System.out.println("NIO: Connection established successfully");
            // Connection established, switch to read mode
            key.interestOps(SelectionKey.OP_READ);
            processEstablishedConnection(key, channel);

        } catch (IOException e) {
            System.err.println("NIO: Connection failed: " + e.getMessage());
            cleanupFailedConnection(key, channel);
        }
    }

    private void processEstablishedConnection(SelectionKey key, SocketChannel channel) throws IOException {
        NetworkAddress destination = validateOutboundChannelState(key, channel);
        System.out.println("NIO: Processing established connection to " + destination);

        try {
            sendQueuedMessages(destination, channel);
            registerOutboundConnectionMetrics(channel, destination);
            System.out.println("NIO: Successfully processed connection to " + destination);

        } catch (IOException e) {
            handleConnectionProcessingFailure(key, channel, destination, e);
            throw e;
        }
    }

    private NetworkAddress validateOutboundChannelState(SelectionKey key, SocketChannel channel) {
        ChannelState channelState = (ChannelState) key.attachment();
        if (channelState == null) {
            throw new IllegalStateException("No ChannelState attached to SelectionKey for channel: " + channel);
        }

        if (!channelState.isOutbound()) {
            throw new IllegalStateException("ChannelState is not outbound for channel: " + channel);
        }

        NetworkAddress destination = channelState.getRemoteAddress();
        if (destination == null) {
            throw new IllegalStateException("No remote address in ChannelState for channel: " + channel);
        }

        return destination;
    }

    private void registerOutboundConnectionMetrics(SocketChannel channel, NetworkAddress destination) {
        metricsCollector.registerOutboundConnection(channel.toString(), destination);
    }

    private void handleConnectionProcessingFailure(SelectionKey key, SocketChannel channel, NetworkAddress destination, IOException e) {
        System.err.println("NIO: Failed to process connection to " + destination + ": " + e.getMessage());
        cleanupFailedConnection(key, channel);
    }

    private void cleanupFailedConnection(SelectionKey key, SocketChannel channel) {
        try {
            key.cancel();
            channel.close();
        } catch (IOException cleanupException) {
            System.err.println("NIO: Error during failed connection cleanup: " + cleanupException.getMessage());
        }
    }

    private static final int MAX_FRAMES_PER_READ = 64;

    private void handleRead(SelectionKey key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("SelectionKey cannot be null");
        }
        
        SocketChannel channel = (SocketChannel) key.channel();
        ChannelState channelState = validateChannelState(key, channel);
        
        ReadFrame readFrame = channelState.getReadFrame();
        readFromChannel(channel, readFrame);
        
        // Update activity
        channelState.updateActivity();
    }

    private ChannelState validateChannelState(SelectionKey key, SocketChannel channel) {
        ChannelState channelState  = (ChannelState) key.attachment();
        if (channelState == null) {
            throw new IllegalStateException("No ChannelState attached to SelectionKey for channel: " + channel);
        }
        return channelState;
    }

    private void readFromChannel(SocketChannel channel, ReadFrame readFrame) {
        int framesProcessed = 0;
        int messagesDecoded = 0;

        try {
            while (framesProcessed < MAX_FRAMES_PER_READ) {
                ReadResult result = readFrame.readFrom(channel);

                if (result.isConnectionClosed()) {
                    handleConnectionClosed(channel);
                    return;
                }

                if (result.hasFramingError()) {
                    handleFramingError(channel, result.error(), readFrame);
                    return; //  Do not continue on framing errors
                }

                if (!result.isFrameComplete()) {
                    break; // wait for more data
                }

                ReadFrame.Frame frame = readFrame.complete();
                routeMessage(channel, frame);
                framesProcessed++;
                messagesDecoded++;
            }
        } catch (Exception e) {
            handleUnexpectedReadError(channel, e);
        }

        // Update metrics
        if (messagesDecoded > 0) {
            updateReadMetrics(channel, messagesDecoded);
            System.out.println("[NIO][handleRead] Decoded " + messagesDecoded + " messages, processed " + framesProcessed + " frames");
        }
    }

    private void handleConnectionClosed(SocketChannel channel) {
        System.out.println("[NIO][handleRead] Channel closed by peer: " + channel);
        cleanupConnection(channel);
    }

    private void handleFramingError(SocketChannel channel, Throwable error, ReadFrame frame) {
        System.out.println("[NIO][framing] Invalid frame from " + getRemoteAddress(channel) + ": " + error.getMessage() + " (resetting ReadFrame)");
        frame.reset();
    }

    private void handleUnexpectedReadError(SocketChannel channel, Exception e) {
        System.err.println("[NIO][handleRead] Unexpected error on " + getRemoteAddress(channel) + ": " + e.getMessage());
        e.printStackTrace();
    }

    private void routeMessage(SocketChannel channel, ReadFrame.Frame frame) {
        try {
            // Convert ByteBuffer to byte array for decoding
            Message message = decodeMessage(frame);
            routeInboundMessage(new InboundMessage(message, new MessageContext(message, channel, getChannelSourceAddress(channel))));

        } catch (Exception e) {
            System.out.println("[NIO][framing] Error decoding message frame: " + e.getMessage());
        }
    }

    private Message decodeMessage(ReadFrame.Frame frame) {
        ByteBuffer payload = frame.getPayload();
        byte[] msgBytes = new byte[payload.remaining()];
        payload.get(msgBytes);

        Message message = codec.decode(msgBytes, Message.class);
        return message;
    }

    private String getRemoteAddress(SocketChannel channel) {
        try {
            return channel.getRemoteAddress().toString();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private void updateReadMetrics(SocketChannel channel, int messagesDecoded) {
        if (messagesDecoded > 0) {
            // Record message count as bytes (approximate - each message counts as 1 byte for metrics)
            // This is a reasonable approximation since we can't get exact byte counts from ReadFrame
            metricsCollector.recordBytesReceived(channel.toString(), messagesDecoded);
        }
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

            metricsCollector.recordBytesSent(channel.toString(), bytesWritten);
        }

        // If no more pending writes, switch back to read mode
        if (!channelState.hasPendingWrites()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            System.out.println("NIO: No more pending writes, disabled write interest");
        }
    }

    private void processOutboundMessages() {
        // Process new outbound messages
        sendOutboundMessages();
        // Process messages pending for the connection establishment
        sendMessagesPendingForConnectionEstablishment();
    }

    private void sendOutboundMessages() {
        int processedCount = processOutboundQueue();
        logProcessedCount(processedCount);
    }

    private int processOutboundQueue() {
        List<Message> messages = new ArrayList<>();
        state.drainOutboundMessages(messages, config.maxOutboundPerTick());
        
        return messages.stream()
            .peek(this::logProcessingMessage)
            .mapToInt(message -> {
                sendMessageOverNetwork(message);
                return 1;
            })
            .sum();
    }

    private void logProcessingMessage(Message message) {
        System.out.println("NIO: Processing outbound message from " +
                message.source() + " to " + message.destination());
    }

    private void logProcessedCount(int processedCount) {
        if (processedCount > 0) {
            System.out.println("NIO: Processed " + processedCount + " outbound messages");
        }
    }

    /**
     * Processes messages that are pending during the connection establishment.
     * Attempts to send queued messages if connections become available.
     */
    private void sendMessagesPendingForConnectionEstablishment() {
        if (hasNoPendingMessages()) {
            return;
        }

        for (Map.Entry<NetworkAddress, Queue<Message>> entry : state.getPendingMessages().entrySet()) {
            NetworkAddress destination = entry.getKey();
            Queue<Message> queue = entry.getValue();

            if (!queue.isEmpty()) {
                sendIfConnected(destination, queue);
            }
        }
    }

    private boolean hasNoPendingMessages() {
        return state.getPendingMessages().isEmpty();
    }

    private void sendIfConnected(NetworkAddress destination, Queue<Message> queue) {
        logPendingMessages(destination, queue);
        SocketChannel channel = getOutboundChannelForDestination(destination);
        if (isChannelConnected(channel)) {
            sendQueuedMessagesToConnectedChannel(destination, channel);
        } else if (isChannelOpenButNotConnected(channel)) {
            keepPendingMessagesForConnectingChannel(destination, channel);
        } else {
            clearPendingMessagesForDisconnectedDestination(destination, channel);
        }
    }

    private void logPendingMessages(NetworkAddress destination, Queue<Message> queue) {
        String messageTypes = extractMessageTypes(queue);
        System.out.println("NIO: Found " + state.getPendingMessageCount(destination) + 
                " pending messages for " + destination + " (types: " + messageTypes + ")");
    }

    private String extractMessageTypes(Queue<Message> queue) {
        return queue.stream()
                .map(msg -> msg.messageType().toString())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private SocketChannel getOutboundChannelForDestination(NetworkAddress destination) {
        return state.getOutboundConnection(destination);
    }

    private void sendIfConnected(NetworkAddress destination, SocketChannel channel) {
        if (isChannelConnected(channel)) {
            sendQueuedMessagesToConnectedChannel(destination, channel);
        } else if (isChannelOpenButNotConnected(channel)) {
            keepPendingMessagesForConnectingChannel(destination, channel);
        } else {
            clearPendingMessagesForDisconnectedDestination(destination, channel);
        }
    }

    private boolean isChannelConnected(SocketChannel channel) {
        return channel != null && channel.isConnected();
    }

    private boolean isChannelOpenButNotConnected(SocketChannel channel) {
        return channel != null && channel.isOpen() && !channel.isConnected();
    }

    private void sendQueuedMessagesToConnectedChannel(NetworkAddress destination, SocketChannel channel) {
        System.out.println("NIO: Channel connected, sending queued messages to " + destination);
        try {
            sendQueuedMessages(destination, channel);
        } catch (IOException e) {
            logQueuedMessageSendFailure(destination, e);
        }
    }

    private void logQueuedMessageSendFailure(NetworkAddress destination, IOException e) {
        System.err.println("Failed to send queued messages to " + destination + ": " + e);
    }

    private void keepPendingMessagesForConnectingChannel(NetworkAddress destination, SocketChannel channel) {
        System.out.println("NIO: Channel exists but not yet connected for " + destination +
                " (channel=" + channel + ", connected=" + channel.isConnected() +
                ", open=" + channel.isOpen() + ") - keeping pending messages");
    }

    private void clearPendingMessagesForDisconnectedDestination(NetworkAddress destination, SocketChannel channel) {
        System.out.println("NIO: No channel for " + destination + " (channel=" + channel + ")");
        System.out.println("NIO: Clearing pending messages for disconnected destination " + destination);
        state.clearPendingMessages(destination);
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

    /**
     * Sends a message to the destination over the network via an outbound socket channel.
     *
     * This method follows the One Connection per Direction principle:
     * - Always uses (and if needed, creates) an outbound connection for sending.
     * - Never attempts to send on inbound connections.
     * This ensures clear, deterministic connection management and avoids ambiguity.
     */
    private void sendMessageOverNetwork(Message message) {
        try {
            NetworkAddress destination = message.destination();
            SocketChannel channel = acquireOutboundChannel(destination);
            logSendAttempt(destination, channel);

            if (isChannelReadyForSending(channel)) {
                sendMessageOnReadyChannel(message, channel);
            } else {
                queueMessageForLaterDelivery(destination, message);
            }
        } catch (IOException e) {
            System.err.println("Failed to send message: " + e);
            // Message failed to send, could retry or log
        }
    }

    private SocketChannel acquireOutboundChannel(NetworkAddress destination) throws IOException {
        SocketChannel channel = getOrCreateOutboundChannel(destination);
        System.out.println("NIO: Using outbound connection for " + destination);
        return channel;
    }

    private void logSendAttempt(NetworkAddress destination, SocketChannel channel) {
        System.out.println("NIO: sendMessageDirectly to " + destination +
                " (channel=" + channel + ", connected=" +
                (channel != null ? channel.isConnected() : "null") + ")");
    }

    private boolean isChannelReadyForSending(SocketChannel channel) {
        return channel != null && channel.isConnected();
    }

    private void sendMessageOnReadyChannel(Message message, SocketChannel channel) throws IOException {
        WriteFrame writeFrame = createWriteFrame(message);
        int bytesWritten = attemptImmediateWrite(channel, writeFrame);
        
        if (writeFrame.hasRemaining()) {
            queuePartialWrite(channel, writeFrame);
        } else {
            logCompleteSend();
        }
        
        recordSendMetrics(channel, bytesWritten);
    }

    private WriteFrame createWriteFrame(Message message) {
        byte[] messageData = codec.encode(message);
        return new WriteFrame(messageData);
    }

    private int attemptImmediateWrite(SocketChannel channel, WriteFrame writeFrame) throws IOException {
        int bytesWritten = writeFrame.write(channel);
        System.out.println("NIO: Wrote " + bytesWritten + " bytes immediately, remaining=" + writeFrame.remaining());
        return bytesWritten;
    }

    private void queuePartialWrite(SocketChannel channel, WriteFrame writeFrame) {
        SelectionKey key = channel.keyFor(selector);
        if (key != null) {
            ChannelState channelState = (ChannelState) key.attachment();
            if (channelState != null) {
                channelState.addPendingWrite(writeFrame);
                enableWriteInterest(key);
                System.out.println("NIO: Registered for write events, frame queued");
            }
        }
    }

    private void enableWriteInterest(SelectionKey key) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    private void logCompleteSend() {
        System.out.println("NIO: Message sent completely");
    }

    private void recordSendMetrics(SocketChannel channel, int bytesWritten) {
        metricsCollector.recordBytesSent(channel.toString(), bytesWritten);
    }

    private void queueMessageForLaterDelivery(NetworkAddress destination, Message message) {
        state.addPendingMessage(destination, message);
        System.out.println("NIO: Message queued for later delivery, pending count for " + destination +
                ": " + state.getPendingMessageCount(destination));
    }


    /**
     * Gets an existing outbound channel or creates a new one if needed.
     * Follows the One Connection per Direction principle by always using outbound connections.
     */
    private SocketChannel getOrCreateOutboundChannel(NetworkAddress address) throws IOException {
        SocketChannel channel = state.getOutboundConnection(address);
        logChannelStatus(address, channel);

        if (isChannelUsable(channel)) {
            logUsingExistingChannel(channel);
            return channel;
        }

        logCreatingNewChannel(channel);
        return createNewOutboundChannel(address);
    }

    private void logChannelStatus(NetworkAddress address, SocketChannel channel) {
        System.out.println("NIO: getOrCreateOutboundChannel for " + address +
                " (existing channel=" + channel +
                ", connected=" + (channel != null ? channel.isConnected() : "null") +
                ", isOpen=" + (channel != null ? channel.isOpen() : "null") + ")");
    }

    private boolean isChannelUsable(SocketChannel channel) {
        return channel != null && channel.isOpen();
    }

    private void logUsingExistingChannel(SocketChannel channel) {
        System.out.println("NIO: Using existing channel (connected=" + channel.isConnected() + ")");
    }

    private void logCreatingNewChannel(SocketChannel channel) {
        if (channel != null) {
            System.out.println("NIO: Existing channel not open, creating new one");
        } else {
            System.out.println("NIO: No existing channel, creating new one");
        }
    }

    private SocketChannel createNewOutboundChannel(NetworkAddress address) throws IOException {
        SocketChannel channel = createAndConfigureChannel();
        SelectionKey key = establishConnection(channel, address);
        setupChannelState(key, address);
        storeChannel(address, channel);
        return channel;
    }

    private SocketChannel createAndConfigureChannel() throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);
        return channel;
    }

    private SelectionKey establishConnection(SocketChannel channel, NetworkAddress address) throws IOException {
        boolean connected = channel.connect(new InetSocketAddress(address.ipAddress(), address.port()));
        
        if (!connected) {
            logConnectionInProgress();
            return channel.register(selector, SelectionKey.OP_CONNECT);
        } else {
            logImmediateConnection();
            return channel.register(selector, SelectionKey.OP_READ);
        }
    }

    private void logConnectionInProgress() {
        System.out.println("NIO: Connection in progress, registering for connect events");
    }

    private void logImmediateConnection() {
        System.out.println("NIO: Immediate connection, registering for read events");
    }

    private void setupChannelState(SelectionKey key, NetworkAddress address) {
        ChannelState channelState = ChannelState.forOutbound(address);
        key.attach(channelState);
    }

    private void storeChannel(NetworkAddress address, SocketChannel channel) {
        state.putOutboundConnection(address, channel);
        System.out.println("NIO: Stored new channel in outboundConnections map");
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
            NetworkAddress actualClientAddress = NetworkAddress.from(localAddress);

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

            metricsCollector.cleanupConnection(channel.toString());

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
                return NetworkAddress.from(remoteAddress);
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

        System.out.println("NIO: routeInboundMessage called for " + message.messageType() + " from " +
                message.source() + " to " + message.destination());

        try {
            state.addInboundMessage(im);
            System.out.println("NIO: Message added to receive queue for " + message.destination() +
                    ", queue size: " + state.getInboundMessageQueueSize());
        } catch (Exception e) {
            System.err.println("NIO: Exception in routeInboundMessage: " + e.getMessage());
            e.printStackTrace();
        }
    }



    /**
     * Closes all network resources.
     */
    public void close() {
        try {
            System.out.println("NIO: Starting network shutdown...");

            clearAllPendingMessages();

            if (selector.isOpen()) {
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
    
    /**
     * Gets the current size of the outbound message queue.
     * This is primarily used for testing purposes.
     *
     * @return the number of messages in the outbound queue
     */
    public int getOutboundQueueSize() {
        return state.getOutboundQueueSize();
    }
}