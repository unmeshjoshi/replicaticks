package replicated.network;

import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

class InboundConnections {
    // INBOUND CONNECTIONS: Connections to us (for receiving from clients, sending responses)
    //TODO:NetworkAddress to be replaced by ProcessID.
    private final Map<NetworkAddress, InboundChannel> channels = new ConcurrentHashMap<>();

    public void put(NetworkAddress address, SocketChannel channel) {
        channels.put(address, new InboundChannel(address, channel));
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

        InboundChannel inboundChannel = channels.get(source);
        if (inboundChannel == null) {
            // If no channel for the source exists, create one
            inboundChannel = new InboundChannel(source, message.messageContext().getSourceChannel());
            channels.put(source, inboundChannel);
        }
        inboundChannel.addMessage(message);
    }

    public SocketChannel remove(NetworkAddress address) {
        InboundChannel channel = channels.remove(address);
        return channel.getChannel();
    }

    public SocketChannel getChannel(NetworkAddress address) {
        return channels.get(address).getChannel();
    }

    public int getTotalQueueSize() {
        return channels.values().stream()
                .mapToInt(channel -> channel.getTotalMessageCount())
                .sum();
    }


    public Map<NetworkAddress, SocketChannel> getChannels() {
        return channels.values().stream().collect(Collectors.toMap(InboundChannel::getRemoteAddress, InboundChannel::getChannel));
    }

    public List<InboundMessage> getMessageToProcess(NetworkConfig config) {
        int totalInboundMessages = 0;
        int maxPerTick = config.maxInboundPerTick();
        int maxPerSource = 5; // Maximum messages per source per round
        List<InboundMessage> messages = new ArrayList<>(maxPerTick);
        for (NetworkAddress source : channels.keySet()) {
            if (totalInboundMessages >= maxPerTick) break;
            InboundChannel channel = channels.get(source);
            totalInboundMessages += channel.transferMessagesTo(messages, maxPerSource);
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
                .map(InboundChannel::getChannel).collect(Collectors.toList());
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
}
