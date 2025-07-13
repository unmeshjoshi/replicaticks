package replicated.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-channel state management for NIO operations using ReadFrame and WriteFrame abstractions.
 * 
 * This class provides clean abstractions for reading and writing framed messages,
 * separating concerns and making the code more maintainable.
 */
public class ChannelState {
    
    private final ReadFrame readFrame;
    private final Queue<WriteFrame> pendingWrites;
    private volatile long lastActivityTime;
    
    public ChannelState() {
        this.readFrame = new ReadFrame();
        this.pendingWrites = new ConcurrentLinkedQueue<>();
        this.lastActivityTime = System.currentTimeMillis();
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
    
    @Override
    public String toString() {
        return String.format("ChannelState{lastActivity=%d, pendingWrites=%d, readFrame=%s}", 
                           lastActivityTime, pendingWrites.size(), readFrame);
    }
} 