package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
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

    public NioConnection(NetworkAddress remoteAddress, SocketChannel channel) {
        this(remoteAddress, channel, null);
    }

    public NioConnection(NetworkAddress remoteAddress, SocketChannel channel, MessageCodec codec) {
        this.remoteAddress = remoteAddress;
        this.channel = channel;
        this.codec = codec;
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

    public int sendOutgoingMessages(Selector selector, int maxMessagesPerDestination) throws IOException {
        List<Message> toSend = new ArrayList<>();
        int drained = drainOutgoingMessages(toSend, maxMessagesPerDestination);
        sendMessagesOverNetwork(selector, new ArrayDeque<>(toSend));
        return drained;
    }
}