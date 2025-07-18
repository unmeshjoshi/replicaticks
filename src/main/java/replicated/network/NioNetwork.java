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

/**
 * NIO-based network implementation for deterministic distributed simulation.
 * Uses Java NIO for non-blocking network I/O while maintaining tick() behavior.
 * <p>
 * ================================================================================
 * ONE CONNECTION PER DIRECTION PRINCIPLE
 * ================================================================================
 * <p>
 * This implementation follows a strict "One Connection per Direction" principle:
 * <p>
 * 1. INBOUND CONNECTIONS (Server Role)
 * - Created when clients connect to our bound server sockets
 * - Used ONLY for receiving messages from clients
 * - Never used for sending messages
 * - Stored in NetworkState.inboundConnections
 * <p>
 * 2. OUTBOUND CONNECTIONS (Client Role)
 * - Created when we need to send messages to other nodes
 * - Used ONLY for sending messages to destinations
 * - Never used for receiving messages
 * - Stored in NetworkState.outboundConnections
 * <p>
 * BENEFITS:
 * - Clear separation of concerns: each connection has a single purpose
 * - Deterministic behavior: no ambiguity about which connection to use
 * - Simplified connection management: no complex routing logic
 * - Better resource utilization: connections are purpose-built
 * - Easier debugging: connection state is predictable
 * <p>
 * IMPLEMENTATION DETAILS:
 * - send() method always uses outbound connections (creates if needed)
 * - sendOnChannel() is used for responses on existing inbound connections
 * - No cross-usage: inbound connections never send, outbound connections never receive
 * - Connection establishment is asynchronous and handled in tick() cycles
 * <p>
 * ================================================================================
 * <p>
 * Key features:
 * - Non-blocking I/O using NIO channels
 * - Multiple server sockets can be bound to different addresses
 * - Maintains message queues for delivery
 * - Supports network partitioning for testing
 * - Thread-safe for concurrent access
 * - Deterministic simulation behavior
 */
public class NioNetwork implements Network {

    private final NetworkConfig config;
    private final NetworkFaultConfig faultConfig;
    //Simple collector of network metrics.
    private final MetricsCollector metricsCollector = new MetricsCollector();


    private final MessageCodec codec;
    private final Selector selector;
    // Network state management

    private final NetworkState state;

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
        state = new NetworkState(codec, config);
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

    /**
     * Sends a message through the network using outbound connections.
     * <p>
     * This method is the primary entry point for sending messages and implements
     * the "One Connection per Direction" principle:
     * <p>
     * ROLE IN THE ARCHITECTURE:
     * - Creates and manages OUTBOUND connections for sending messages
     * - Never uses inbound connections for sending (maintains separation)
     * - Queues messages for asynchronous processing in tick() cycles
     * - Handles connection establishment, retry logic, and error recovery
     * <p>
     * CONNECTION MANAGEMENT:
     * - For each destination, maintains at most one outbound connection
     * - Creates new connections asynchronously when needed
     * - Reuses existing connections for subsequent messages to same destination
     * - Handles connection failures gracefully with message queuing
     * <p>
     * MESSAGE PROCESSING FLOW:
     * 1. Message is validated and added to outbound queue
     * 2. In tick() cycle, messages are processed from queue
     * 3. For each message, outbound connection is acquired/created
     * 4. Message is sent on the outbound connection
     * 5. If connection not ready, message is queued for later delivery
     * <p>
     * RELATIONSHIP TO sendOnChannel():
     * - send(): Creates outbound connections for new messages/requests
     * - sendOnChannel(): Uses existing connections for responses
     * - Together they provide complete connection management strategy
     * <p>
     * DETERMINISTIC BEHAVIOR:
     * - All network operations happen in tick() cycles
     * - Connection establishment is non-blocking and asynchronous
     * - Message ordering is preserved within each connection
     * - Fault injection (partitions, packet loss) is applied consistently
     *
     * @param message the message to send (must not be null)
     * @throws IllegalArgumentException if message is null
     */
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

        try {
            OutboundChannel outboundChannel = getOrCreateOutboundChannel(message.destination());
            // Add to outbound queue for async processing
            outboundChannel.addOutboundMessage(message);
            System.out.println("NIO: Message added to outbound queue, queue size: " + state.getOutboundQueueSize());
        } catch (IOException e) {
            System.out.println("NIO: Failed to get outbound channel for " + message.destination() + ": " + e.getMessage());
        }
    }

    /**
     * Gets an existing outbound channel or creates a new one if needed.
     * Follows the One Connection per Direction principle by always using outbound connections.
     */
    private OutboundChannel getOrCreateOutboundChannel(NetworkAddress address) throws IOException {
        OutboundChannel channel = state.outboundConnections.get(address);
        if (!isChannelUsable(channel)) {
            logCreatingNewChannel(channel);
            return createNewOutboundChannel(address);
        }

        logChannelStatus(address, channel.getChannel());
        logUsingExistingChannel(channel.getChannel());
        return channel;

    }

    private void logChannelStatus(NetworkAddress address, SocketChannel channel) {
        System.out.println("NIO: getOrCreateOutboundChannel for " + address +
                " (existing channel=" + channel +
                ", connected=" + (channel != null ? channel.isConnected() : "null") +
                ", isOpen=" + (channel != null ? channel.isOpen() : "null") + ")");
    }

    private boolean isChannelUsable(OutboundChannel channel) {
        return channel != null && channel.getChannel().isOpen();
    }

    private void logUsingExistingChannel(SocketChannel channel) {
        System.out.println("NIO: Using existing channel (connected=" + channel.isConnected() + ")");
    }

    private void logCreatingNewChannel(OutboundChannel channel) {
        if (channel != null) {
            System.out.println("NIO: Existing channel not open, creating new one");
        } else {
            System.out.println("NIO: No existing channel, creating new one");
        }
    }

    private OutboundChannel createNewOutboundChannel(NetworkAddress address) throws IOException {
        SocketChannel channel = createAndConfigureChannel();
        SelectionKey key = establishConnection(channel, address);
        setupChannelState(key, address);
        storeChannel(address, channel);
        return state.outboundConnections.get(address);
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
        state.outboundConnections.put(address, channel);
        System.out.println("NIO: Stored new channel in outboundConnections map");
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

            // In tick() method, before selectNow():
            for (SelectionKey key : selector.keys()) {
                if (key.channel() instanceof SocketChannel) {
                    System.out.println("Interest ops: " + key.interestOps() +
                            ", isWritable: " + key.isWritable());
                }
            }

            processSelectedKeys(selector.selectedKeys());

            // Process outbound messages
            processOutboundMessages();

            // Process inbound messages and deliver to callback
            processInboundMessages();

            toggleBackpressureIfNecessary();

        } catch (IOException e) {
            throw new RuntimeException("Error in network tick", e);
        }
    }

    private void toggleBackpressureIfNecessary() {
        state.inboundConnections.toggleBackpressureIfNecessary(selector, config);
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
                e.printStackTrace();
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

        //TODO: Once we move ProcessID based mapping, we initialise the time we process the first message
        //      which will have the source ID as part of the message.
        // Store in inbound connections map (client connections to us)
        state.inboundConnections.put(clientAddress, clientChannel);

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
        return channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
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
                return;
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
            state.outboundConnections.sendPendingMessages(selector, destination);
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
        ChannelState channelState = (ChannelState) key.attachment();
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
        System.out.println("Handling write = " + key);
        if (channelState == null) {
            System.err.println("NIO: No channel state attached to SelectionKey for write operation");
            return;
        }
        // Process pending writes from the queue
        while (channelState.hasPendingWrites()) {
            System.out.println("Writing pending writes = " + channelState);
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

    private void processOutboundMessages() throws IOException {
        // Process new outbound messages
        state.outboundConnections.sendOutboundMessages(selector);
        state.outboundConnections.processPendingMessages(selector);
    }

    /**
     * Process inbound messages from per-source queues and deliver to registered callback.
     * This implements true round-robin processing across all sources to prevent starvation.
     */
    private void processInboundMessages() {
        if (messageCallback == null) return;
        List<InboundMessage> messages = state.inboundConnections.getMessageToProcess(this.config);

        for (InboundMessage message : messages) {
            deliverInbound(message);
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

    /**
     * Establishes a connection to the destination and returns the actual local address
     * assigned by the network layer. This simulates the OS assigning an ephemeral port.
     * <p>
     * This method is primarily used for testing purposes to establish connections
     * and get the actual local address assigned by the OS.
     *
     * @param destination the destination address to connect to
     * @return the actual local address assigned for this connection
     * @throws IllegalArgumentException if destination is null
     */
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
            //TODO: We will map this to ProcessID later.
            state.outboundConnections.put(destination, channel);

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
                    // Inbound connection closed - remove from inbound connections map
                    NetworkAddress clientAddress = channelState.getRemoteAddress();
                    if (clientAddress != null) {
                        state.inboundConnections.remove(clientAddress);
                        System.out.println("NIO: Inbound connection closed and removed from map: " + clientAddress);
                    }
                } else {
                    // Outbound connection closed - remove from outbound connections map and clear pending messages
                    NetworkAddress destination = channelState != null ? channelState.getRemoteAddress() : null;
                    if (destination != null) {
                        state.removeOutboundConnection(destination);
                        state.clearPendingMessages(destination);
                        System.out.println("NIO: Outbound connection closed and removed from map: " + destination);
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
            state.inboundConnections.addInboundMessage(im);
            System.out.println("NIO: Message added to receive queue for " + message.destination() +
                    ", queue size: " + state.inboundConnections.getTotalQueueSize());
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

            state.clearAllPendingMessages();

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


    // === NEW PUBLIC API =========================================================

    /**
     * Sends a message directly on an already-established SocketChannel.
     * <p>
     * This method is a key part of the "One Connection per Direction" principle:
     * <p>
     * ROLE IN THE ARCHITECTURE:
     * - Used for RESPONSES on existing INBOUND connections
     * - Enables request-response patterns without opening new connections
     * - Preserves connection affinity (response goes back on same channel as request)
     * - Provides performance optimization by avoiding connection establishment overhead
     * <p>
     * USAGE PATTERN:
     * 1. Client sends request via send() → creates outbound connection
     * 2. Server receives request on inbound connection → processes request
     * 3. Server sends response via sendOnChannel() → uses same inbound connection
     * 4. Client receives response on same connection as request
     * <p>
     * IMPLEMENTATION DETAILS:
     * - Only works on already-established channels (typically inbound connections)
     * - Queues the message for writing in the next tick() cycle
     * - Enables OP_WRITE interest to ensure the selector processes the write
     * - Maintains the deterministic simulation model
     * <p>
     * RELATIONSHIP TO send():
     * - send(): Creates outbound connections for new messages
     * - sendOnChannel(): Uses existing connections for responses
     * - Together they implement the complete connection management strategy
     *
     * @param channel the already-established SocketChannel to send on
     * @param message the message to send
     * @throws IllegalArgumentException if channel or message is null
     * @throws IllegalStateException    if channel is closed
     */
    @Override
    public void sendOnChannel(SocketChannel channel, Message message) {
        System.out.println("Writing message on channel " + message.messageType() + " to " + message.destination());
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
                System.out.println("adding pending write to channelState = " + message.messageType());
                channelState.addPendingWrite(writeFrame);
            } else {
                // Create a default channel state if none exists
                channelState = new ChannelState();
                key.attach(channelState);
                System.out.println("adding pending write to channelState = " + message.messageType());
                channelState.addPendingWrite(writeFrame);
            }
        }

        // Ensure OP_WRITE interest so selector wakes up next tick
        if (key != null && key.isValid()) {
            int oldOps = key.interestOps();
            System.out.println("NIO: Before setting OP_WRITE - oldOps=" + oldOps);

            key.interestOps(oldOps | SelectionKey.OP_WRITE);

            int newOps = key.interestOps();
            System.out.println("NIO: After setting OP_WRITE - newOps=" + newOps +
                    ", expected=" + (oldOps | SelectionKey.OP_WRITE));

            if (newOps != (oldOps | SelectionKey.OP_WRITE)) {
                System.err.println("NIO: Interest ops update failed!");
            }
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

    /**
     * Gets the client channel for a specific destination address.
     * This is primarily used for testing purposes.
     *
     * @param destination the destination address to look up
     * @return the SocketChannel for the destination, or null if no connection exists
     */
    public SocketChannel getClientChannel(NetworkAddress destination) {
        return state.getClientChannel(destination);
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

    // === TESTING METHODS =====================================================

    /**
     * Returns the inbound connections map for testing purposes.
     * These are client connections to us (for receiving from clients, sending responses).
     */
    public Map<NetworkAddress, SocketChannel> getInboundConnections() {
        return state.inboundConnections.getChannels();
    }

    /**
     * Returns the outbound connections map for testing purposes.
     * These are our connections to replicas (for sending to replicas).
     */
    public OutboundConnections getOutboundConnections() {
        return state.getOutboundConnections();
    }

    // === QUEUE SIZE METHODS FOR TESTING =====================================

    /**
     * Returns the queue size for a specific destination for testing purposes.
     */
    public int getOutboundQueueSizeForDestination(NetworkAddress destination) {
        return state.getOutboundQueueSizeForDestination(destination);
    }

    public boolean isBackpressureEnabled() {
        return state.inboundConnections.isBackpressureEnabled();
    }
}