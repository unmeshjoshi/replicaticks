package replicated.network;

import replicated.messaging.NetworkAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-channel state management for NIO operations using ReadFrame and WriteFrame abstractions.
 * 
 * This class provides clean abstractions for reading and writing framed messages,
 * separating concerns and making the code more maintainable.
 * 
 * Supports both inbound and outbound connections with remote address tracking.
 */
public class ChannelState {
    
    private final ReadFrame readFrame;
    private final Queue<WriteFrame> pendingWrites;
    private volatile long lastActivityTime;
    
    // New fields for connection type and remote address
    private NetworkAddress remoteAddress;  // For both inbound and outbound
    private boolean isInbound;             // Connection type flag
    
    public ChannelState() {
        this.readFrame = new ReadFrame();
        this.pendingWrites = new ConcurrentLinkedQueue<>();
        this.lastActivityTime = System.currentTimeMillis();
        this.isInbound = false; // Default to outbound
    }
    
    /**
     * Factory method for creating outbound connection state.
     */
    public static ChannelState forOutbound(NetworkAddress destination) {
        ChannelState state = new ChannelState();
        state.remoteAddress = destination;
        state.isInbound = false;
        return state;
    }
    
    /**
     * Factory method for creating inbound connection state.
     */
    public static ChannelState forInbound(NetworkAddress remoteClient) {
        ChannelState state = new ChannelState();
        state.remoteAddress = remoteClient;
        state.isInbound = true;
        return state;
    }
    
    /**
     * Gets the read frame for this channel.
     */
    public ReadFrame getReadFrame() {
        return readFrame;
    }
    
    /**
     * Gets the queue of pending write frames.
     */
    public Queue<WriteFrame> getPendingWrites() {
        return pendingWrites;
    }
    
    /**
     * Updates the last activity time for this channel.
     */
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Gets the last activity time for this channel.
     */
    public long getLastActivityTime() {
        return lastActivityTime;
    }
    
    /**
     * Checks if this channel has pending writes.
     */
    public boolean hasPendingWrites() {
        return !pendingWrites.isEmpty();
    }
    
    /**
     * Adds a write frame to the pending writes queue.
     */
    public void addPendingWrite(WriteFrame writeFrame) {
        pendingWrites.offer(writeFrame);
    }
    
    /**
     * Checks if this channel is idle for the given duration.
     */
    public boolean isIdle(long maxIdleTimeMs) {
        return System.currentTimeMillis() - lastActivityTime > maxIdleTimeMs;
    }
    
    /**
     * Cleans up resources associated with this channel state.
     */
    public void cleanup() {
        readFrame.reset();
        pendingWrites.clear();
    }
    
    // New methods for connection type and remote address
    
    /**
     * Returns true if this is an inbound connection.
     */
    public boolean isInbound() {
        return isInbound;
    }
    
    /**
     * Returns true if this is an outbound connection.
     */
    public boolean isOutbound() {
        return !isInbound;
    }
    
    /**
     * Gets the remote address for this connection.
     * For outbound: the destination we're connecting to
     * For inbound: the remote client that connected to us
     */
    public NetworkAddress getRemoteAddress() {
        return remoteAddress;
    }
    
    /**
     * Gets the destination address (only for outbound connections).
     * Returns null for inbound connections.
     */
    public NetworkAddress getDestination() {
        return isOutbound() ? remoteAddress : null;
    }
    
    @Override
    public String toString() {
        return String.format("ChannelState{remoteAddress=%s, isInbound=%s, lastActivity=%d, pendingWrites=%d, readFrame=%s}", 
                           remoteAddress, isInbound, lastActivityTime, pendingWrites.size(), readFrame);
    }
} 