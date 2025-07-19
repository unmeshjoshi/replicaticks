package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.NetworkAddress;

import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Higher-level metadata for network channels, separate from NIO-specific ChannelState.
 * <p>
 * This class manages application-level concerns like message queues, backpressure state,
 * and connection metadata. The NIO-specific state is managed separately via SelectionKey
 * attachments.
 */
public class InboundChannel {

    private final NetworkAddress remoteAddress;
    private final SocketChannel channel;
    private final Queue<InboundMessage> receivedMessages;
    private boolean backpressureEnabled;
    private long lastMessageTime;

    public InboundChannel(NetworkAddress remoteAddress, SocketChannel channel) {
        this.remoteAddress = remoteAddress;
        this.channel = channel;
        this.receivedMessages = new ArrayDeque<>();
        this.backpressureEnabled = false;
        this.lastMessageTime = System.nanoTime();
    }

    /**
     * Gets the remote address for this channel.
     */
    public NetworkAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Gets the underlying SocketChannel for this connection.
     */
    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * Gets the message queue for this channel.
     */
    public Queue<InboundMessage> getReceivedMessages() {
        return receivedMessages;
    }

    /**
     * Checks if backpressure is currently enabled for this channel.
     */
    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    /**
     * Enables backpressure for this channel.
     */
    public void enableBackpressure() {
        this.backpressureEnabled = true;
    }

    /**
     * Disables backpressure for this channel.
     */
    public void disableBackpressure() {
        this.backpressureEnabled = false;
    }

    /**
     * Adds a message to this channel's queue.
     */
    public void addMessage(InboundMessage message) {
        receivedMessages.offer(message);
        lastMessageTime = System.nanoTime();
    }

    /**
     * Removes and returns the next message from this channel's queue.
     */
    public InboundMessage pollMessage() {
        return receivedMessages.poll();
    }

    /**
     * Peeks at the next message without removing it.
     */
    public InboundMessage peekMessage() {
        return receivedMessages.peek();
    }

    /**
     * Checks if this channel has pending messages.
     */
    public boolean hasMessages() {
        return !receivedMessages.isEmpty();
    }

    /**
     * Gets the number of messages currently in the queue.
     */
    public int getMessageCount() {
        return receivedMessages.size();
    }

    /**
     * Gets the total number of messages that have been processed by this channel.
     */
    public int getTotalMessageCount() {
        return receivedMessages.size();
    }

    /**
     * Gets the timestamp of the last message received.
     */
    public long getLastMessageTime() {
        return lastMessageTime;
    }

    /**
     * Checks if this channel has been idle for the given duration.
     */
    public boolean isIdle(long maxIdleTimeMs) {
        return System.nanoTime() - lastMessageTime > maxIdleTimeMs;
    }

    /**
     * Clears all messages from this channel's queue.
     */
    public void clearMessages() {
        receivedMessages.clear();
    }

    /**
     * Drains messages from this channel's queue up to the specified limit.
     * Returns the number of messages drained.
     */
    public int transferMessagesTo(List<InboundMessage> destination, int maxMessages) {
        int drained = 0;
        InboundMessage message;
        while (drained < maxMessages && (message = receivedMessages.poll()) != null) {
            destination.add(message);
            drained++;
        }
        return drained;
    }

    @Override
    public String toString() {
        return String.format("ChannelInfo{remoteAddress=%s, channel=%s, messageCount=%d, backpressure=%s, lastMessage=%d}",
                remoteAddress, channel, receivedMessages.size(), backpressureEnabled, lastMessageTime);
    }
} 