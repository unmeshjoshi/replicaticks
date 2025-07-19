package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Manages outbound connections and message queues for sending to other nodes.
 * <p>
 * FOLLOWS "ONE CONNECTION PER DIRECTION" PRINCIPLE:
 * - outboundChannels: Each destination has its own OutboundChannel with all state
 * - Each OutboundChannel contains: SocketChannel, outbound queue, pending messages
 * <p>
 * QUEUE DESIGN:
 * - Per-destination outbound queues: Fair sending, isolation from slow destinations
 * - Pending messages: For messages waiting for connection establishment
 */
class OutboundConnections {

    // OUTBOUND CHANNELS: Each destination has its own OutboundChannel with all state
    private final Map<NetworkAddress, OutboundChannel> outboundChannels = new ConcurrentHashMap<>();
    private final NetworkConfig config;
    private MessageCodec codec;

    public OutboundConnections(NetworkConfig config, MessageCodec codec) {
        this.config = config;
        this.codec = codec;
    }

    /**
     * Adds an outbound connection for the specified destination.
     */
    public void put(NetworkAddress address, SocketChannel channel) {
        outboundChannels.computeIfAbsent(address, (addr) -> new OutboundChannel(codec, addr, channel));
    }

    public OutboundChannel get(NetworkAddress address) {
        return outboundChannels.get(address);

    }

    /**
     * Removes an outbound connection for the specified destination.
     */
    public SocketChannel remove(NetworkAddress address) {
        OutboundChannel outboundChannel = outboundChannels.remove(address);
        if (outboundChannel != null) {
            SocketChannel channel = outboundChannel.getChannel();
            outboundChannel.cleanup();
            return channel;
        }
        return null;
    }

    /**
     * Gets the outbound connection for the specified destination.
     */
    public SocketChannel getChannel(NetworkAddress address) {
        OutboundChannel outboundChannel = outboundChannels.get(address);
        return outboundChannel != null ? outboundChannel.getChannel() : null;
    }

    /**
     * Checks if an outbound connection exists for the specified destination.
     */
    public boolean hasConnection(NetworkAddress address) {
        OutboundChannel outboundChannel = outboundChannels.get(address);
        return outboundChannel != null && outboundChannel.isConnected();
    }

    /**
     * Gets all outbound destinations.
     */
    public Set<NetworkAddress> getDestinations() {
        return outboundChannels.keySet();
    }

    /**
     * Gets all outbound channels.
     */
    public Map<NetworkAddress, OutboundChannel> getOutboundChannels() {
        return outboundChannels;
    }

    /**
     * Gets the entry set of outbound channels for iteration.
     */
    public Set<Map.Entry<NetworkAddress, OutboundChannel>> getOutboundChannelsEntrySet() {
        return outboundChannels.entrySet();
    }

    /**
     * Adds a message to the outbound queue for the specified destination.
     */
    public void addOutboundMessage(Message message) {
        NetworkAddress destination = message.destination();
        OutboundChannel outboundChannel = outboundChannels.get(destination);
        outboundChannel.addOutboundMessage(message);
    }

    /**
     * Polls a message from any outbound queue using round-robin for fair sending.
     */
    public Message pollOutboundMessage() {
        // Round-robin through destinations for fair sending
        for (OutboundChannel outboundChannel : outboundChannels.values()) {
            Message message = outboundChannel.pollOutboundMessage();
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    /**
     * Drains messages from outbound queues up to the specified limit using round-robin.
     */
    public int drainOutboundMessages(Collection<Message> collection, int maxElements) {
        int totalDrained = 0;
        int remainingElements = maxElements;

        // Round-robin through destinations for fair sending
        for (OutboundChannel outboundChannel : outboundChannels.values()) {
            if (remainingElements <= 0) break;

            // Create a temporary queue to collect messages from this channel
            Queue<Message> tempQueue = new LinkedBlockingQueue<>();
            int drained = outboundChannel.drainOutboundMessages(tempQueue, remainingElements);
            if (drained > 0) {
                // Transfer messages from temp queue to the target collection
                tempQueue.forEach(collection::add);
                totalDrained += drained;
                remainingElements -= drained;
            }
        }

        return totalDrained;
    }

    /**
     * Gets the total size of all outbound queues.
     */
    public int getTotalQueueSize() {
        return outboundChannels.values().stream()
                .mapToInt(OutboundChannel::getOutboundQueueSize)
                .sum();
    }

    /**
     * Gets the queue size for a specific destination.
     */
    public int getQueueSizeForDestination(NetworkAddress destination) {
        OutboundChannel outboundChannel = outboundChannels.get(destination);
        return outboundChannel != null ? outboundChannel.getOutboundQueueSize() : 0;
    }

    /**
     * Gets all outbound channels that match the given predicate.
     */
    public List<SocketChannel> getAllChannels(Predicate<OutboundChannel> predicate) {
        return outboundChannels.values().stream()
                .filter(predicate)
                .map(OutboundChannel::getChannel)
                .filter(channel -> channel != null) // Filter out null channels
                .toList();
    }

    /**
     * Gets the pending message count for the specified address.
     */
    public int getPendingMessageCount(NetworkAddress address) {
        OutboundChannel outboundChannel = outboundChannels.get(address);
        return outboundChannel != null ? outboundChannel.getPendingMessageCount() : 0;
    }

    /**
     * Clears pending messages for the specified address.
     */
    public void clearPendingMessages(NetworkAddress address) {
        OutboundChannel outboundChannel = outboundChannels.get(address);
        if (outboundChannel != null) {
            outboundChannel.clearPendingMessages();
        }
    }

    /**
     * Clears all pending messages.
     */
    public void clearAllPendingMessages() {
        outboundChannels.values().forEach(OutboundChannel::clearPendingMessages);
    }

    /**
     * Gets the total message count across all queues.
     */
    public int getTotalMessageCount() {
        return outboundChannels.values().stream()
                .mapToInt(OutboundChannel::getTotalMessageCount)
                .sum();
    }

    private void sendIfConnected(Selector selector, NetworkAddress destination, OutboundChannel outboundChannel) throws IOException {
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
        sendIfConnected(selector, destination, outboundChannels.get(destination));
    }

    public void processPendingMessages(Selector selector) throws IOException {
        for (OutboundChannel outboundChannel : outboundChannels.values()) {
            if (!outboundChannel.isConnected()) {
                continue;
            }
            outboundChannel.sendPendingMessages(selector);
        }
    }


    public void sendOutboundMessages(Selector selector) {
        outboundChannels.values().forEach(outboundChannel -> {
            try {
                if (!outboundChannel.isConnected()) {
                    return;
                }
                outboundChannel.sendOutboundMessages(selector, config.maxOutboundPerTick());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public boolean isEmpty() {
        return outboundChannels.isEmpty();
    }

    public int size() {
        return outboundChannels.size();
    }
}