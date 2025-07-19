package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Encapsulates all state for a single outbound connection to a destination.
 * 
 * This class manages the SocketChannel, outbound message queue, and pending messages
 * for a single destination, providing a cohesive interface for outbound connection management.
 */
public class OutboundChannel {

    private final MessageCodec codec;
    private final NetworkAddress destination;
    private SocketChannel channel;
    private final BlockingQueue<Message> outboundQueue;
    private final Queue<Message> pendingMessages;
    private volatile long lastActivityTime;
    private volatile int totalMessagesSent;
    
    public OutboundChannel(MessageCodec codec, NetworkAddress destination, SocketChannel channel) {
        this.codec = codec;
        this.destination = destination;
        this.channel = channel;
        this.outboundQueue = new LinkedBlockingQueue<>();
        this.pendingMessages = new LinkedBlockingQueue<>();
        this.lastActivityTime = System.currentTimeMillis();
        this.totalMessagesSent = 0;
    }
    
    /**
     * Gets the destination address for this outbound channel.
     */
    public NetworkAddress getDestination() {
        return destination;
    }
    
    /**
     * Gets the underlying SocketChannel for this connection.
     */
    public SocketChannel getChannel() {
        return channel;
    }
    
    /**
     * Sets the SocketChannel for this connection.
     */
    public void setChannel(SocketChannel channel) {
        this.channel = channel;
        updateActivity();
    }
    
    /**
     * Checks if this channel has an established connection.
     */
    public boolean isConnected() {
        return channel != null && channel.isOpen() && channel.isConnected();
    }
    
    /**
     * Gets the outbound message queue for this destination.
     */
    public BlockingQueue<Message> getOutboundQueue() {
        return outboundQueue;
    }
    
    /**
     * Gets the pending messages queue for this destination.
     */
    public Queue<Message> getPendingMessages() {
        return pendingMessages;
    }
    
    /**
     * Adds a message to the outbound queue.
     */
    public void addOutboundMessage(Message message) {
        outboundQueue.add(message);
        updateActivity();
    }
    
    /**
     * Polls a message from the outbound queue.
     */
    public Message pollOutboundMessage() {
        Message message = outboundQueue.poll();
        if (message != null) {
            totalMessagesSent++;
            updateActivity();
        }
        return message;
    }
    
    /**
     * Peeks at the next message without removing it.
     */
    public Message peekOutboundMessage() {
        return outboundQueue.peek();
    }
    
    /**
     * Checks if this channel has pending outbound messages.
     */
    public boolean hasOutboundMessages() {
        return !outboundQueue.isEmpty();
    }
    
    /**
     * Gets the number of pending outbound messages.
     */
    public int getOutboundQueueSize() {
        return outboundQueue.size();
    }
    
    /**
     * Drains outbound messages up to the specified limit.
     */
    public int drainOutboundMessages(Collection<Message> destination, int maxMessages) {
        int drained = outboundQueue.drainTo(destination, maxMessages);
        if (drained > 0) {
            totalMessagesSent += drained;
            updateActivity();
        }
        return drained;
    }
    
    /**
     * Adds a message to the pending messages queue.
     */
    public void addPendingMessage(Message message) {
        pendingMessages.add(message);
        updateActivity();
    }
    
    /**
     * Polls a message from the pending messages queue.
     */
    public Message pollPendingMessage() {
        return pendingMessages.poll();
    }
    
    /**
     * Gets the number of pending messages.
     */
    public int getPendingMessageCount() {
        return pendingMessages.size();
    }
    
    /**
     * Checks if this channel has pending messages.
     */
    public boolean hasPendingMessages() {
        return !pendingMessages.isEmpty();
    }
    
    /**
     * Clears all pending messages.
     */
    public void clearPendingMessages() {
        pendingMessages.clear();
    }
    
    /**
     * Gets the total number of messages sent through this channel.
     */
    public int getTotalMessagesSent() {
        return totalMessagesSent;
    }
    
    /**
     * Gets the last activity time for this channel.
     */
    public long getLastActivityTime() {
        return lastActivityTime;
    }
    
    /**
     * Checks if this channel has been idle for the given duration.
     */
    public boolean isIdle(long maxIdleTimeMs) {
        return System.currentTimeMillis() - lastActivityTime > maxIdleTimeMs;
    }
    
    /**
     * Updates the last activity time for this channel.
     */
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Gets the total message count (outbound + pending).
     */
    public int getTotalMessageCount() {
        return outboundQueue.size() + pendingMessages.size();
    }
    
    /**
     * Cleans up resources associated with this channel.
     */
    public void cleanup() {
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                // Log but don't fail
            }
            channel = null;
        }
        outboundQueue.clear();
        pendingMessages.clear();
    }
    
    @Override
    public String toString() {
        return String.format("OutboundChannel{destination=%s, connected=%s, outboundQueue=%d, pending=%d, totalSent=%d}", 
                           destination, isConnected(), outboundQueue.size(), pendingMessages.size(), totalMessagesSent);
    }

    public void sendMessagesOverNetwork(Selector selector, Queue<Message> messages) throws IOException {
        if (!isConnected()) {
            addPendingMessages(messages);
        }

        if (messages != null && !messages.isEmpty()) {
            System.out.println("NIO: sendQueuedMessages called for " + destination);

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

            System.out.println("NIO: Sent " + sentCount + " queued messages, remaining in queue: " + messages.size());
        } else {
//            System.out.println("NIO: No pending messages queue found for " + destination);
        }
    }

    private void addPendingMessages(Queue<Message> messages) {
        pendingMessages.addAll(messages);
    }

    public void sendPendingMessages(Selector selector) throws IOException {
        sendMessagesOverNetwork(selector, getPendingMessages());
    }

    public int sendOutboundMessages(Selector selector, int maxMessagesPerDestination) throws IOException {
        List<Message> toSend = new ArrayList<>();
        int drained = drainOutboundMessages(toSend, maxMessagesPerDestination);
        sendMessagesOverNetwork(selector, new ArrayDeque<>(toSend));
        return drained;
    }
}