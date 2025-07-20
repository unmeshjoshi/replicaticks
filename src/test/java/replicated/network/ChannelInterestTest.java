package replicated.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.channels.SelectionKey;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The suite exercises all the critical bit‑mask scenarios hit in production:
 * Enabling each op from a “zero” state
 * Enabling one op when the other is already set
 * Disabling each op when both bits are set
 * Disabling each op when it’s the only bit set
 * Querying each op in both true/false states
 */
public class ChannelInterestTest {
    private StubSelectionKey key;
    private ChannelInterest interest;

    /**
     * Stub implementation of SelectionKey to track interestOps state.
     */
    static class StubSelectionKey extends SelectionKey {
        private int ops;

        @Override
        public int interestOps() {
            return ops;
        }

        @Override
        public SelectionKey interestOps(int ops) {
            this.ops = ops;
            return this;
        }

        @Override
        public SelectableChannel channel() {
            throw new UnsupportedOperationException("Not needed for stub");
        }

        @Override
        public Selector selector() {
            throw new UnsupportedOperationException("Not needed for stub");
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void cancel() {
            // No-op for stub
        }

        @Override
        public int readyOps() {
            throw new UnsupportedOperationException("Not needed for stub");
        }
    }

    @BeforeEach
    void setUp() {
        key = new StubSelectionKey();
        interest = new ChannelInterest(key);
    }

    @Test
    void testEnableRead() {
        key.ops = 0;
        interest.enableRead();
        assertEquals(SelectionKey.OP_READ, key.ops);
    }

    @Test
    void testEnableReadWithWriteAlreadySet() {
        key.ops = SelectionKey.OP_WRITE;
        interest.enableRead();
        assertEquals(SelectionKey.OP_WRITE | SelectionKey.OP_READ, key.ops);
    }

    @Test
    void testDisableRead() {
        key.ops = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
        interest.disableRead();
        assertEquals(SelectionKey.OP_WRITE, key.ops);
    }

    @Test
    void testDisableReadWhenOnlyReadSet() {
        key.ops = SelectionKey.OP_READ;
        interest.disableRead();
        assertEquals(0, key.ops);
    }

    @Test
    void testEnableWrite() {
        key.ops = 0;
        interest.enableWrite();
        assertEquals(SelectionKey.OP_WRITE, key.ops);
    }

    @Test
    void testEnableWriteWithReadAlreadySet() {
        key.ops = SelectionKey.OP_READ;
        interest.enableWrite();
        assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.ops);
    }

    @Test
    void testDisableWrite() {
        key.ops = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
        interest.disableWrite();
        assertEquals(SelectionKey.OP_READ, key.ops);
    }

    @Test
    void testDisableWriteWhenOnlyWriteSet() {
        key.ops = SelectionKey.OP_WRITE;
        interest.disableWrite();
        assertEquals(0, key.ops);
    }

    @Test
    void testIsReadEnabled() {
        key.ops = SelectionKey.OP_READ;
        assertTrue(interest.isReadEnabled());
    }

    @Test
    void testIsReadDisabled() {
        key.ops = SelectionKey.OP_WRITE;
        assertFalse(interest.isReadEnabled());
    }

    @Test
    void testIsWriteEnabled() {
        key.ops = SelectionKey.OP_WRITE;
        assertTrue(interest.isWriteEnabled());
    }

    @Test
    void testIsWriteDisabled() {
        key.ops = SelectionKey.OP_READ;
        assertFalse(interest.isWriteEnabled());
    }
}
