package replicated.network;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Handles reading framed messages from a socket channel.
 * Separates header reading from payload reading for better abstraction.
 * Supports both legacy 4-byte length prefix and new 9-byte header formats.
 */
public class ReadFrame {
    private final ByteBuffer headerBuffer;
    private ByteBuffer payloadBuffer = null;
    private int streamId = -1;
    private byte frameType = -1;
    private int payloadLength = -1;
    private boolean headerComplete = false;
    private boolean legacyFormat = true; // Default to legacy format for compatibility

    public ReadFrame() {
        // Start with legacy 4-byte length prefix format
        this.headerBuffer = ByteBuffer.allocate(4);
        this.legacyFormat = true;
    }

    public ReadFrame(boolean useNewFormat) {
        if (useNewFormat) {
            // 4 bytes streamId + 1 byte frameType + 4 bytes length = 9 bytes header
            this.headerBuffer = ByteBuffer.allocate(9);
            this.legacyFormat = false;
        } else {
            // Legacy 4-byte length prefix format
            this.headerBuffer = ByteBuffer.allocate(4);
            this.legacyFormat = true;
        }
    }

    /**
     * Reads data from the channel, handling both header and payload phases.
     * 
     * @param channel The socket channel to read from
     * @return true if a complete frame has been read, false if more data is needed
     * @throws IOException if an I/O error occurs
     * @throws EOFException if the channel is closed
     */
    public boolean read(SocketChannel channel) throws IOException {
        if (!headerComplete) {
            return readHeader(channel);
        } else {
            return readPayload(channel);
        }
    }

    private boolean readHeader(SocketChannel channel) throws IOException {
        if (headerBuffer.hasRemaining()) {
            int read = channel.read(headerBuffer);
            if (read == -1) throw new EOFException();
            if (headerBuffer.hasRemaining()) return false;
        }

        // Header is complete, parse it
        headerBuffer.flip();
        
        if (legacyFormat) {
            // Legacy format: just 4-byte length
            payloadLength = headerBuffer.getInt();
            streamId = 0; // Default for legacy
            frameType = 0; // Default for legacy
        } else {
            // New format: streamId + frameType + length
            streamId = headerBuffer.getInt();
            frameType = headerBuffer.get();
            payloadLength = headerBuffer.getInt();
        }
        
        // Validate payload length
        if (payloadLength < 0 || payloadLength > 10_000_000) { // 10MB max
            throw new IOException("Invalid payload length: " + payloadLength);
        }
        
        payloadBuffer = ByteBuffer.allocate(payloadLength);
        headerBuffer.clear();
        headerComplete = true;
        
        return false; // Still need to read payload
    }

    private boolean readPayload(SocketChannel channel) throws IOException {
        if (payloadBuffer.hasRemaining()) {
            int read = channel.read(payloadBuffer);
            if (read == -1) throw new EOFException();
            return !payloadBuffer.hasRemaining();
        }
        return true;
    }

    /**
     * Creates a complete frame from the read data.
     * Call this only after read() returns true.
     * 
     * @return The complete frame
     */
    public Frame complete() {
        if (!headerComplete || payloadBuffer == null || payloadBuffer.hasRemaining()) {
            throw new IllegalStateException("Frame is not complete");
        }
        
        payloadBuffer.flip();
        Frame frame = new Frame(streamId, frameType, payloadBuffer);
        
        // Reset for next frame
        payloadBuffer = null;
        headerComplete = false;
        streamId = -1;
        frameType = -1;
        payloadLength = -1;
        
        return frame;
    }

    /**
     * Checks if we're currently reading a header.
     */
    public boolean isReadingHeader() {
        return !headerComplete;
    }

    /**
     * Checks if we're currently reading payload.
     */
    public boolean isReadingPayload() {
        return headerComplete && payloadBuffer != null && payloadBuffer.hasRemaining();
    }

    /**
     * Gets the current payload length being read.
     */
    public int getPayloadLength() {
        return payloadLength;
    }

    /**
     * Gets the current stream ID being read.
     */
    public int getStreamId() {
        return streamId;
    }

    /**
     * Gets the current frame type being read.
     */
    public byte getFrameType() {
        return frameType;
    }

    /**
     * Checks if this frame is using the legacy format.
     */
    public boolean isLegacyFormat() {
        return legacyFormat;
    }

    /**
     * Resets the frame reader for reuse.
     */
    public void reset() {
        headerBuffer.clear();
        payloadBuffer = null;
        headerComplete = false;
        streamId = -1;
        frameType = -1;
        payloadLength = -1;
    }

    @Override
    public String toString() {
        return String.format("ReadFrame{legacy=%s, headerComplete=%s, payloadLength=%d, streamId=%d, frameType=%d}", 
                           legacyFormat, headerComplete, payloadLength, streamId, frameType);
    }

    /**
     * Represents a complete frame with stream ID, type, and payload.
     */
    public static class Frame {
        private final int streamId;
        private final byte frameType;
        private final ByteBuffer payload;

        public Frame(int streamId, byte frameType, ByteBuffer payload) {
            this.streamId = streamId;
            this.frameType = frameType;
            this.payload = payload;
        }

        public int getStreamId() { return streamId; }
        public byte getFrameType() { return frameType; }
        public ByteBuffer getPayload() { return payload; }

        @Override
        public String toString() {
            return String.format("Frame{streamId=%d, type=%d, payloadSize=%d}", 
                               streamId, frameType, payload.remaining());
        }
    }
} 