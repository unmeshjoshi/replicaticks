package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Manages outbound connections and message queues for sending to other nodes.
 * <p>
 * FOLLOWS "ONE CONNECTION PER DIRECTION" PRINCIPLE:
 * - outboundChannels: Each destination has its own NioConnection with all state
 * - Each NioConnection contains: SocketChannel, outbound queue, pending messages
 * <p>
 * QUEUE DESIGN:
 * - Per-destination outbound queues: Fair sending, isolation from slow destinations
 * - Pending messages: For messages waiting for connection establishment
 */
class Connections {

    // OUTBOUND CHANNELS: Each destination has its own NioConnection with all state
    private final Map<NetworkAddress, NioConnection> channels = new ConcurrentHashMap<>();
    private final NetworkConfig config;
    private MessageCodec codec;
    private final MetricsCollector metricsCollector;

    public Connections(NetworkConfig config, MessageCodec codec, MetricsCollector metricsCollector) {
        this.config = config;
        this.codec = codec;
        this.metricsCollector = metricsCollector;
    }

    /**
     * Adds an outbound connection for the specified destination.
     */
    public void put(NetworkAddress address, SocketChannel channel, ChannelState channelState, Selector selector) {
        channels.computeIfAbsent(address, (addr) -> new NioConnection(addr, channel, channelState, codec, metricsCollector, selector, this, NioConnection.ConnectionType.INBOUND));
    }

    public NioConnection get(NetworkAddress address) {
        return channels.get(address);

    }


    /**
     * Gets the outbound connection for the specified destination.
     */
    public SocketChannel getChannel(NetworkAddress address) {
        NioConnection outboundChannel = channels.get(address);
        return outboundChannel != null ? outboundChannel.getChannel() : null;
    }

    /**
     * Checks if an outbound connection exists for the specified destination.
     */
    public boolean hasConnection(NetworkAddress address) {
        NioConnection outboundChannel = channels.get(address);
        return outboundChannel != null && outboundChannel.isConnected();
    }

    /**
     * Gets all outbound destinations.
     */
    public Set<NetworkAddress> getDestinations() {
        return channels.keySet();
    }

    /**
     * Gets all outbound channels.
     */
    public Map<NetworkAddress, NioConnection> getNioConnections() {
        return channels;
    }

    /**
     * Adds a message to the outbound queue for the specified destination.
     */
    public void addOutboundMessage(Message message) {
        NetworkAddress destination = message.destination();
        NioConnection outboundChannel = channels.get(destination);
        outboundChannel.addOutgoingMessage(message);
    }


    /**
     * Gets the total size of all outbound queues.
     */
    /**
     * Gets the queue size for a specific destination.
     */
    public int getQueueSizeForDestination(NetworkAddress destination) {
        NioConnection connection = channels.get(destination);
        return connection != null ? connection.getOutgoingQueueSize() : 0;
    }

    /**
     * Gets the pending message count for the specified address.
     */
    public int getPendingMessageCount(NetworkAddress address) {
        NioConnection outboundChannel = channels.get(address);
        return outboundChannel != null ? outboundChannel.getPendingMessageCount() : 0;
    }

    /**
     * Clears pending messages for the specified address.
     */
    public void clearPendingMessages(NetworkAddress address) {
        NioConnection outboundChannel = channels.get(address);
        if (outboundChannel != null) {
            outboundChannel.clearPendingMessages();
        }
    }

    /**
     * Clears all pending messages.
     */
    public void clearAllPendingMessages() {
        channels.values().forEach(NioConnection::clearPendingMessages);
    }

    /**
     * Gets the total message count across all queues.
     */
    public int getTotalMessageCount() {
        return channels.values().stream()
                .mapToInt(NioConnection::getTotalMessageCount)
                .sum();
    }

    private void sendIfConnected(Selector selector, NetworkAddress destination, NioConnection outboundChannel) throws IOException {
        logPendingMessages(destination, outboundChannel.getPendingMessages());
        if (outboundChannel.isConnected()) {
            outboundChannel.sendPendingMessages(selector);
        } else {
            outboundChannel.clearPendingMessages();
        }
    }

    private void logPendingMessages(NetworkAddress destination, Queue<Message> queue) {
        String messageTypes = extractMessageTypes(queue);
        System.out.println("NIO: Found " + getPendingMessageCount(destination) +
                " pending messages for " + destination + " (types: " + messageTypes + ")");
    }

    private String extractMessageTypes(Queue<Message> queue) {
        return queue.stream()
                .map(msg -> msg.messageType().toString())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    public void sendPendingMessages(Selector selector, NetworkAddress destination) throws IOException {
        sendIfConnected(selector, destination, channels.get(destination));
    }

    public void processPendingMessages(Selector selector) throws IOException {
        for (NioConnection outboundChannel : channels.values()) {
            if (!outboundChannel.isConnected()) {
                continue;
            }
            outboundChannel.sendPendingMessages(selector);
        }
    }


    public void sendOutboundMessages(Selector selector) {
        channels.values().forEach(outboundChannel -> {
            try {
                if (!outboundChannel.isConnected()) {
                    return;
                }
                outboundChannel.sendOutgoingMessages(selector, config.maxOutboundPerTick());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    public int size() {
        return channels.size();
    }


    public void put(NetworkAddress address, NioConnection nioConnection) {
        channels.put(address, nioConnection);
    }

    public void addInboundMessage(InboundMessage message) {
        NetworkAddress source = message.message().source();
        //TODO: Once NetworkAddress is replaced by the ProcessID all this if-else
        //      goes away as every message has a source and a destination processID, as a message
        //      passing contract.

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

        NioConnection NioConnection = channels.get(source);
        if (NioConnection == null) {
            System.out.println("No connection for = " + source);
            Exception e = new Exception("No connection for " + source);
            e.printStackTrace();
        }
//
//        if (NioConnection == null) {
//            // If no channel for the source exists, create one
//            NioConnection = new NioConnection(source, message.messageContext().getSourceChannel());
//            channels.put(source, NioConnection);
//        }
        NioConnection.addIncomingMessage(message);
    }

    public SocketChannel remove(NetworkAddress address) {
        NioConnection channel = channels.remove(address);
        return channel.getChannel();
    }

    public int getTotalQueueSize() {
        return channels.values().stream()
                .mapToInt(channel -> channel.getTotalMessageCount())
                .sum();
    }


    public Map<NetworkAddress, SocketChannel> getChannels() {
        return channels.values().stream().collect(Collectors.toMap(NioConnection::getRemoteAddress, NioConnection::getChannel));
    }

    public List<InboundMessage> getMessageToProcess(NetworkConfig config) {
        int totalInboundMessages = 0;
        int maxPerTick = config.maxInboundPerTick();
        int maxPerSource = 5; // Maximum messages per source per round
        List<InboundMessage> messages = new ArrayList<>(maxPerTick);
        for (NetworkAddress source : channels.keySet()) {
            if (totalInboundMessages >= maxPerTick) break;
            NioConnection channel = channels.get(source);
            totalInboundMessages += channel.drainIncomingMessages(messages, maxPerSource);
        }
        return messages;
    }

    boolean backPressureEnabled = false;
    public boolean isBackpressureEnabled() {
        return backPressureEnabled;
    }

    public void backPressureEnabled() {
        backPressureEnabled = true;
    }

    public void backPressureDisabled() {
        backPressureEnabled = false;
    }


    private List<SocketChannel> setInterestOpsOnChannels(Selector selector,
                                                         Function<Integer, Integer> interestOpFunction,
                                                         Predicate<Queue<InboundMessage>> predicate) {
        List<SocketChannel> allChannels = getAllChannels(predicate);
        allChannels.stream().forEach(channel -> {
            if (channel != null && channel.isOpen()) {
                SelectionKey key = channel.keyFor(selector);
                if (key != null && key.isValid()) {
                    try {
                        int currentOps = key.interestOps();
                        int newOps = interestOpFunction.apply(currentOps);
                        key.interestOps(newOps);
                    } catch (Exception e) {
                        System.err.println("NIO: Error setting interest ops on channel " + channel + ": " + e.getMessage());
                    }
                }
            }
        });
        return allChannels;
    }

    private List<SocketChannel> getAllChannels(Predicate<Queue<InboundMessage>> predicate) {
        return channels.values().stream()
                .filter(channel -> predicate.test(channel.getReceivedMessages()))
                .map(NioConnection::getChannel).collect(Collectors.toList());
    }

    public void toggleBackpressureIfNecessary(Selector selector, NetworkConfig config) {
        // Disable OP_READ (preserve other ops)
        List<SocketChannel> backPressuredChannels = setInterestOpsOnChannels(
                selector,
                currentOps -> currentOps & ~SelectionKey.OP_READ,  // Function to disable READ
                (inboundMessages) -> inboundMessages.size() > config.backpressureHighWatermark()
        );

        // Enable OP_READ (preserve other ops)
        List<SocketChannel> enabledChannels = setInterestOpsOnChannels(
                selector,
                currentOps -> currentOps | SelectionKey.OP_READ,   // Function to enable READ
                (inboundMessages) -> inboundMessages.size() < config.backpressureLowWatermark()
        );

        if (backPressuredChannels.size() > 0) {
            backPressureEnabled();
        } else {
            backPressureDisabled();
        }
    }
    public List<NioConnection> getOutboundConnections() {
        return channels.values().stream()
                .filter(NioConnection::isOutbound).collect(Collectors.toList());
    }

    public List<NioConnection> getInboundConnections() {
        return channels.values().stream()
                .filter(NioConnection::isInbound).collect(Collectors.toList());
    }
}