package replicated.network;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

/**
 * Per-channel state management for NIO operations.
 * 
 * This class prevents data corruption by providing dedicated buffers
 * for each socket channel, following production patterns from Kafka,
 * Cassandra, and other distributed systems.
 */
public class ChannelState {
    
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private final Queue<ByteBuffer> pendingWrites;
    private volatile long lastActivityTime;
    private volatile boolean hasPartialMessage;
    private byte[] partialMessageBytes;
    
    public ChannelState(int bufferSize) {
        this.readBuffer = ByteBuffer.allocate(bufferSize);
        this.writeBuffer = ByteBuffer.allocate(bufferSize);
        this.pendingWrites = new ConcurrentLinkedQueue<>();
        this.lastActivityTime = System.currentTimeMillis();
        this.hasPartialMessage = false;
    }
    
    public ChannelState() {
        this(8192); // Default 8KB buffers
    }
    
    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }
    
    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }
    
    public Queue<ByteBuffer> getPendingWrites() {
        return pendingWrites;
    }
    
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    public long getLastActivityTime() {
        return lastActivityTime;
    }
    
    public boolean hasPartialMessage() {
        return hasPartialMessage;
    }
    
    public void setPartialMessage(byte[] partialBytes) {
        this.partialMessageBytes = partialBytes;
        this.hasPartialMessage = (partialBytes != null && partialBytes.length > 0);
    }
    
    public byte[] getPartialMessageBytes() {
        return partialMessageBytes;
    }
    
    public void clearPartialMessage() {
        this.partialMessageBytes = null;
        this.hasPartialMessage = false;
    }
    
    public boolean isIdle(long maxIdleTimeMs) {
        return System.currentTimeMillis() - lastActivityTime > maxIdleTimeMs;
    }
    
    public void addPendingWrite(ByteBuffer buffer) {
        pendingWrites.offer(buffer);
    }
    
    public boolean hasPendingWrites() {
        return !pendingWrites.isEmpty();
    }
    
    /**
     * Cleanup resources associated with this channel state.
     */
    public void cleanup() {
        readBuffer.clear();
        writeBuffer.clear();
        pendingWrites.clear();
        clearPartialMessage();
    }
    
    @Override
    public String toString() {
        return String.format("ChannelState{lastActivity=%d, pendingWrites=%d, hasPartial=%s}", 
                           lastActivityTime, pendingWrites.size(), hasPartialMessage);
    }
} 