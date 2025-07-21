package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;

/**
 * Unified connection class for both inbound and outbound network communication.
 * Manages incoming, outgoing, and pending messages, backpressure, and connection state.
 */
public class NioConnection {
    private final NetworkAddress remoteAddress;
    private final SocketChannel channel;
    private final MessageCodec codec; // Optional, can be null for inbound-only
    private final MetricsCollector metricsCollector;
    private final Selector selector;

    // Inbound (received) messages
    private final Queue<InboundMessage> incomingMessages;
    private boolean backpressureEnabled;
    private long lastMessageTime;
    private volatile int totalMessagesReceived;

    // Outbound (to be sent) messages
    private final Queue<Message> outgoingMessages;
    private final Queue<Message> pendingMessages;
    private volatile long lastActivityTime;
    private volatile int totalMessagesSent;
    private ChannelState channelState;
    private Connections connections;
    private final ConnectionType connectionType;

    public NetworkAddress getLocalAddress() throws IOException {
        return NetworkAddress.from(
                ((InetSocketAddress) channel.getLocalAddress()));
    }

    public enum ConnectionType {
        INBOUND,    // Server-side: accepted client connection
        OUTBOUND    // Client-side: initiated connection to server
    }

    public NioConnection(NetworkAddress remoteAddress, SocketChannel channel, ChannelState channelState, MessageCodec codec, MetricsCollector metricsCollector, Selector selector, Connections connections, ConnectionType connectionType) {
        this.remoteAddress = remoteAddress;
        this.channel = channel;
        this.channelState = channelState;
        this.codec = codec;
        this.metricsCollector = metricsCollector;
        this.selector = selector;
        this.connections = connections;
        this.connectionType = connectionType;
        this.incomingMessages = new ArrayDeque<>();
        this.outgoingMessages = new ArrayDeque<>();
        this.pendingMessages = new ArrayDeque<>();
        this.backpressureEnabled = false;
        this.lastMessageTime = System.nanoTime();
        this.lastActivityTime = System.currentTimeMillis();
        this.totalMessagesSent = 0;
        this.totalMessagesReceived = 0;
    }

    // ========== CONNECTION MANAGEMENT ==========
    public NetworkAddress getRemoteAddress() { return remoteAddress; }
    public SocketChannel getChannel() { return channel; }
    public boolean isConnected() { return channel != null && channel.isOpen() && channel.isConnected(); }
    public long getLastActivityTime() { return lastActivityTime; }
    public void updateActivity() { this.lastActivityTime = System.currentTimeMillis(); }
    public boolean isIdle(long maxIdleTimeMs) { return System.currentTimeMillis() - lastActivityTime > maxIdleTimeMs; }

    // ========== INCOMING MESSAGE MANAGEMENT ==========
    public void addIncomingMessage(InboundMessage message) {
        incomingMessages.offer(message);
        lastMessageTime = System.nanoTime();
        totalMessagesReceived++;
        updateActivity();
    }
    public InboundMessage pollIncomingMessage() { return incomingMessages.poll(); }
    public InboundMessage peekIncomingMessage() { return incomingMessages.peek(); }
    public boolean hasIncomingMessages() { return !incomingMessages.isEmpty(); }
    public int getIncomingMessageCount() { return incomingMessages.size(); }
    public int getTotalIncomingMessageCount() { return totalMessagesReceived; }
    public long getLastMessageTime() { return lastMessageTime; }
    public int drainIncomingMessages(List<InboundMessage> destination, int maxMessages) {
        int drained = 0;
        InboundMessage message;
        while (drained < maxMessages && (message = incomingMessages.poll()) != null) {
            destination.add(message);
            drained++;
        }
        if (drained > 0) updateActivity();
        return drained;
    }
    public void clearIncomingMessages() { incomingMessages.clear(); }

    // ========== OUTGOING MESSAGE MANAGEMENT ==========
    public void addOutgoingMessage(Message message) {
        outgoingMessages.add(message);
        updateActivity();
    }
    public Message pollOutgoingMessage() {
        Message message = outgoingMessages.poll();
        if (message != null) {
            totalMessagesSent++;
            updateActivity();
        }
        return message;
    }
    public Message peekOutgoingMessage() { return outgoingMessages.peek(); }
    public boolean hasOutgoingMessages() { return !outgoingMessages.isEmpty(); }
    public int getOutgoingMessageCount() { return outgoingMessages.size(); }
    public int getTotalOutgoingMessageCount() { return totalMessagesSent; }
    public int drainOutgoingMessages(Collection<Message> destination, int maxMessages) {
        int drained = 0;
        Message message;
        while (drained < maxMessages && (message = outgoingMessages.poll()) != null) {
            destination.add(message);
            drained++;
        }
        if (drained > 0) {
            totalMessagesSent += drained;
            updateActivity();
        }
        return drained;
    }
    public void clearOutgoingMessages() { outgoingMessages.clear(); }

    // ========== PENDING MESSAGE MANAGEMENT ==========
    public void addPendingMessage(Message message) {
        pendingMessages.add(message);
        updateActivity();
    }
    public Message pollPendingMessage() { return pendingMessages.poll(); }
    public boolean hasPendingMessages() { return !pendingMessages.isEmpty(); }
    public int getPendingMessageCount() { return pendingMessages.size(); }
    public void clearPendingMessages() { pendingMessages.clear(); }
    public Queue<Message> getPendingMessages() { return pendingMessages; }

    // ========== BACKPRESSURE MANAGEMENT ==========
    public boolean isBackpressureEnabled() { return backpressureEnabled; }
    public void enableBackpressure() { this.backpressureEnabled = true; }
    public void disableBackpressure() { this.backpressureEnabled = false; }

    // ========== QUEUE MANAGEMENT ==========
    public int getTotalMessageCount() {
        return incomingMessages.size() + outgoingMessages.size() + pendingMessages.size();
    }
    public void clearAllMessages() {
        incomingMessages.clear();
        outgoingMessages.clear();
        pendingMessages.clear();
    }
    public int getIncomingQueueSize() { return incomingMessages.size(); }
    public int getOutgoingQueueSize() { return outgoingMessages.size(); }
    public int getPendingQueueSize() { return pendingMessages.size(); }

    // ========== RESOURCE MANAGEMENT ==========
    public void cleanup() {
        if (channel != null) {
            try { channel.close(); } catch (Exception e) { /* Log but don't fail */ }
        }
        clearAllMessages();
    }

    // ========== SELECTOR REGISTRATION ==========
    public SelectionKey registerWithSelector(Selector selector, int interestOps) throws IOException {
        return channel.register(selector, interestOps);
    }

    @Override
    public String toString() {
        return String.format("NioConnection{remoteAddress=%s, connected=%s, incoming=%d, outgoing=%d, pending=%d, totalSent=%d, totalReceived=%d, backpressure=%s}",
                remoteAddress, isConnected(), incomingMessages.size(), outgoingMessages.size(),
                pendingMessages.size(), totalMessagesSent, totalMessagesReceived, backpressureEnabled);
    }

    public Queue<InboundMessage> getReceivedMessages() {
        return incomingMessages;
    }

    public void sendPendingMessages(Selector selector) throws IOException {
        sendMessagesOverNetwork(selector, getPendingMessages());
    }


    public void sendMessagesOverNetwork(Selector selector, Queue<Message> messages) throws IOException {
        if (!isConnected()) {
            pendingMessages.addAll(messages);
        }

        if (messages != null && !messages.isEmpty()) {
            System.out.println("NIO: sendQueuedMessages called for " + remoteAddress);

            System.out.println("NIO: Found pending queue with " + messages.size() + " messages");

            Message message;
            int sentCount = 0;
            while ((message = messages.poll()) != null) {
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
                            channelState.addPendingWrite(writeFrame);
                            // Register for write events if not already registered
                            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                            System.out.println("NIO: Partial write, registered for write events");
                    }
                    break; // Stop processing more messages until this one is sent
                }
            }

            System.out.println("NIO: Sent " + sentCount + " queued messages, remaining in queue: " + messages.size());
        } else {
//            System.out.println("NIO: No pending messages queue found for " + destination);
        }
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

    /**
     * Comprehensive connection cleanup to prevent resource leaks.
     * This follows production patterns for proper connection lifecycle management.
     */
    private void cleanupConnection(SocketChannel channel) {
        try {
            // Cancel any selection key (this will also detach the ChannelState)
            SelectionKey key = channel.keyFor(selector);
            if (key != null) {
                NioConnection channelState = (NioConnection) key.attachment();
                key.attach(null); // Clear the attachment
                key.cancel();

                // Determine whether it was inbound or outbound based on ChannelState
                if (channelState != null) {
                    // Inbound connection closed - remove from inbound connections map
                    NetworkAddress clientAddress = channelState.getRemoteAddress();
                    if (clientAddress != null) {
                        connections.remove(clientAddress);
                        System.out.println("NIO: Inbound connection closed and removed from map: " + clientAddress);
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


    /**
     * Gets the source address for a channel based on connection type.
     */
    private NetworkAddress getChannelSourceAddress(SocketChannel channel) {
        // Get ChannelState from SelectionKey attachment
        SelectionKey key = channel.keyFor(selector);
        if (key != null) {
            NioConnection channelState = (NioConnection) key.attachment();
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

    /**
     * Routes inbound messages with preserved context information.
     * This enables proper response routing back to the source channel.
     */
    private void routeInboundMessage(InboundMessage im) {
        Message message = im.message();

        System.out.println("NIO: routeInboundMessage called for " + message.messageType() + " from " +
                message.source() + " to " + message.destination());

        try {
            addInboundMessage(im);
            System.out.println("NIO: Message added to receive queue for " + message.destination() +
                    ", queue size: " + 10); // TODO: Replace with actual queue size
        } catch (Exception e) {
            System.err.println("NIO: Exception in routeInboundMessage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    //This is kind of a callback. So should more be used as a callback onMessage.
    private void addInboundMessage(InboundMessage im) {
        addIncomingMessage(im);
    }


    public int sendOutgoingMessages(Selector selector, int maxMessagesPerDestination) throws IOException {
        List<Message> toSend = new ArrayList<>();
        int drained = drainOutgoingMessages(toSend, maxMessagesPerDestination);
        sendMessagesOverNetwork(selector, new ArrayDeque<>(toSend));
        return drained;
    }

    public static NioConnection forInbound(MessageCodec codec, MetricsCollector metricsCollector, Selector selector, SocketChannel clientChannel, Connections connections) throws IOException {
        configureNonBlocking(clientChannel);
        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
        NetworkAddress clientAddress = extractClientAddress(clientChannel);
        //clientAddress can never be null for inbound connections.
        ChannelState channelState = ChannelState.forInbound(clientAddress);
        NioConnection nioConnection = new NioConnection(clientAddress, clientChannel, channelState, codec, metricsCollector, selector, connections, ConnectionType.INBOUND);
        clientKey.attach(nioConnection);
        return nioConnection;
    }


    public static NioConnection forOutbound(Selector selector, MessageCodec codec, MetricsCollector metricsCollector, Connections connections, NetworkAddress address) throws IOException {
        return forOutbound(selector, codec, metricsCollector, connections, address, false);
    }

    public static NioConnection forOutbound(Selector selector, MessageCodec codec, MetricsCollector metricsCollector, Connections connections, NetworkAddress address, boolean waitTillConnected) throws IOException {
        SocketChannel channel = createAndConfigureChannel(waitTillConnected);
        SelectionKey key = establishConnection(selector, channel, address);
        ChannelState channelState = ChannelState.forOutbound(address);
        NioConnection nioConnection = new NioConnection(address, channel, channelState, codec, metricsCollector, selector, connections, ConnectionType.OUTBOUND);
        System.out.println("NIO: Stored new channel in outboundConnections map");
        key.attach(nioConnection);

        return nioConnection;
    }

    private static SocketChannel createAndConfigureChannel(boolean waitTillConnected) throws IOException {
        SocketChannel channel = SocketChannel.open();
        if (waitTillConnected) {
            channel.configureBlocking(true); //TODO: For tests only
        } else {
            configureNonBlocking(channel);
        }
        return channel;
    }

    private static SelectionKey establishConnection(Selector selector, SocketChannel channel, NetworkAddress address) throws IOException {
        boolean connected = channel.connect(new InetSocketAddress(address.ipAddress(), address.port()));

        if (!connected) {
            logConnectionInProgress();
            return channel.register(selector, SelectionKey.OP_CONNECT);
        } else {
            logImmediateConnection();
            channel.configureBlocking(false); //TODO: For tests only
            return channel.register(selector, SelectionKey.OP_READ);
        }
    }

    private static void logConnectionInProgress() {
        System.out.println("NIO: Connection in progress, registering for connect events");
    }

    private static void logImmediateConnection() {
        System.out.println("NIO: Immediate connection, registering for read events");
    }

    //TODO: Once we move ProcessID based mapping, we initialise the time we process the first message       }

    private static NetworkAddress extractClientAddress(SocketChannel clientChannel) throws IOException {
        InetSocketAddress clientAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
        return NetworkAddress.from(clientAddress);
    }

    private static void configureNonBlocking(SocketChannel channel) throws IOException {
        channel.configureBlocking(false);
    }

    private void logSuccessfulConnection(NetworkAddress clientAddress) {
        System.out.println("NIO: Accepted connection from client: " + clientAddress);
    }

    public void writePendingMessages() throws IOException {
        // Get channel state from SelectionKey attachment

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
        }
    }
    private static final int MAX_FRAMES_PER_READ = 64;

    public void readFromConnection(Selector selector) {
        ReadFrame readFrame = channelState.getReadFrame();
        readFromChannel(channel, readFrame);

        // Update activity
        channelState.updateActivity();
    }

    public boolean isInbound() {
        return connectionType == ConnectionType.INBOUND;
    }

    public boolean isOutbound() {
        return connectionType == ConnectionType.OUTBOUND;
    }
}