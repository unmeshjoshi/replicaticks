package replicated.network;

import java.nio.channels.SelectionKey;

/**
 * Manages explicit read/write interest ops for a NIO SelectionKey.
 * Provides only specific enable/disable methods with no generic toggles
 * to eliminate ambiguity and potential errors.
 */
public class ChannelInterest {
    private final SelectionKey key;

    public ChannelInterest(SelectionKey key) {
        this.key = key;
    }

    /** Enable OP_READ on this key. */
    public void enableRead() {
        key.interestOps(key.interestOps() | SelectionKey.OP_READ);
    }

    /** Disable OP_READ on this key. */
    public void disableRead() {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
    }

    /** Enable OP_WRITE on this key. */
    public void enableWrite() {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    /** Disable OP_WRITE on this key. */
    public void disableWrite() {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    /** Returns true if OP_READ is currently enabled. */
    public boolean isReadEnabled() {
        return (key.interestOps() & SelectionKey.OP_READ) != 0;
    }

    /** Returns true if OP_WRITE is currently enabled. */
    public boolean isWriteEnabled() {
        return (key.interestOps() & SelectionKey.OP_WRITE) != 0;
    }
}
