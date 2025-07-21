package replicated.network;

import replicated.messaging.NetworkAddress;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

class InboundConnections {
    // INBOUND CONNECTIONS: Connections to us (for receiving from clients, sending responses)
    //TODO:NetworkAddress to be replaced by ProcessID.
    private final Map<NetworkAddress, NioConnection> channels = new ConcurrentHashMap<>();

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
}
