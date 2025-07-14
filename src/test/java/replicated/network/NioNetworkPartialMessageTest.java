package replicated.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.messaging.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for partial message handling in NioNetwork.
 * Tests length-prefixed framing with various partial read/write scenarios.
 */
public class NioNetworkPartialMessageTest {

    private final List<NioNetwork> resources = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NioNetwork n : resources) {
            try { n.close(); } catch (Exception ignored) {}
        }
        resources.clear();
    }

    // === Helpers ==============================================================
    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static void spinTicks(NioNetwork n, int count) {
        for (int i = 0; i < count; i++) n.tick();
    }

    private NioNetwork newNetwork() {
        NioNetwork n = new NioNetwork(new JsonMessageCodec());
        resources.add(n);
        return n;
    }

    private NioNetwork newNetwork(int maxInboundPerTick) {
        NioNetwork n = new NioNetwork(new JsonMessageCodec(), maxInboundPerTick);
        resources.add(n);
        return n;
    }

    private Message createTestMessage(NetworkAddress source, NetworkAddress dest, String payload) {
        return new Message(source, dest, MessageType.PING_REQUEST, payload.getBytes(), UUID.randomUUID().toString());
    }

    // === Test 1: Length Header Split Across Reads ============================

    /**
     * Test when the 4-byte length header is split across multiple read operations.
     * This simulates network conditions where the length header arrives in fragments.
     */
    @Test
    public void shouldHandleLengthHeaderSplitAcrossReads() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        // Create a message and encode it
        Message testMessage = createTestMessage(clientAddr, serverAddr, "test-message");
        byte[] encoded = new JsonMessageCodec().encode(testMessage);
        
        // Create length-prefixed buffer
        ByteBuffer fullBuffer = ByteBuffer.allocate(4 + encoded.length);
        fullBuffer.putInt(encoded.length);
        fullBuffer.put(encoded);
        fullBuffer.flip();

        // Split the buffer to simulate partial reads
        ByteBuffer firstRead = ByteBuffer.allocate(2); // Only first 2 bytes of length
        ByteBuffer secondRead = ByteBuffer.allocate(2 + encoded.length); // Rest of length + payload
        
        firstRead.put(fullBuffer.array(), 0, 2);
        secondRead.put(fullBuffer.array(), 2, 2 + encoded.length);
        
        firstRead.flip();
        secondRead.flip();

        // Simulate partial reads by directly manipulating the channel
        SocketChannel clientChannel = getClientChannel(client, serverAddr);
        assertNotNull(clientChannel, "Client channel should be established");

        // Write first partial read
        clientChannel.write(firstRead);
        spinTicks(client, 1);
        spinTicks(server, 1);
        
        // Verify no message received yet (length header incomplete)
        assertEquals(0, received.get(), "No message should be received with partial length header");

        // Write second partial read
        clientChannel.write(secondRead);
        spinTicks(client, 1);
        spinTicks(server, 1);

        // Wait for message processing
        for (int i = 0; i < 100 && received.get() == 0; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(1, received.get(), "Message should be received after complete length header");
    }

    /**
     * Test when multiple length headers are split across reads.
     */
    @Test
    public void shouldHandleMultipleLengthHeadersSplitAcrossReads() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        // Create two messages
        Message msg1 = createTestMessage(clientAddr, serverAddr, "message-1");
        Message msg2 = createTestMessage(clientAddr, serverAddr, "message-2");
        
        byte[] encoded1 = new JsonMessageCodec().encode(msg1);
        byte[] encoded2 = new JsonMessageCodec().encode(msg2);

        // Write first message and first 2 bytes of second length header
        ByteBuffer part1 = ByteBuffer.allocate(4 + encoded1.length + 2);
        part1.putInt(encoded1.length);
        part1.put(encoded1);
        int len2 = encoded2.length;
        part1.put((byte)((len2 >> 24) & 0xFF));
        part1.put((byte)((len2 >> 16) & 0xFF));
        part1.flip();

        // Write last 2 bytes of second length header and payload
        ByteBuffer part2 = ByteBuffer.allocate(2 + encoded2.length);
        part2.put((byte)((len2 >> 8) & 0xFF));
        part2.put((byte)(len2 & 0xFF));
        part2.put(encoded2);
        part2.flip();

        SocketChannel clientChannel = getClientChannel(client, serverAddr);
        assertNotNull(clientChannel, "Client channel should be established");

        // Write first part (first message + partial second length header)
        clientChannel.write(part1);
        spinTicks(client, 1);
        spinTicks(server, 1);

        // Verify first message received
        for (int i = 0; i < 50 && received.get() == 0; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }
        assertEquals(1, received.get(), "First message should be received");

        // Write remaining part
        clientChannel.write(part2);
        spinTicks(client, 1);
        spinTicks(server, 1);

        // Wait for second message
        for (int i = 0; i < 100 && received.get() < 2; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(2, received.get(), "Both messages should be received");
    }

    // === Test 2: Message Payload Split Across Reads ==========================

    /**
     * Test when message payload is split across multiple read operations.
     */
    @Test
    public void shouldHandleMessagePayloadSplitAcrossReads() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        // Create a large message
        String largePayload = "x".repeat(1000); // 1KB payload
        Message testMessage = createTestMessage(clientAddr, serverAddr, largePayload);
        byte[] encoded = new JsonMessageCodec().encode(testMessage);
        
        // Create length-prefixed buffer
        ByteBuffer fullBuffer = ByteBuffer.allocate(4 + encoded.length);
        fullBuffer.putInt(encoded.length);
        fullBuffer.put(encoded);
        fullBuffer.flip();

        // Split the buffer to simulate partial reads
        int splitPoint = 4 + encoded.length / 2; // Split in middle of payload
        ByteBuffer firstRead = ByteBuffer.allocate(splitPoint);
        ByteBuffer secondRead = ByteBuffer.allocate(4 + encoded.length - splitPoint);
        
        firstRead.put(fullBuffer.array(), 0, splitPoint);
        secondRead.put(fullBuffer.array(), splitPoint, 4 + encoded.length - splitPoint);
        
        firstRead.flip();
        secondRead.flip();

        SocketChannel clientChannel = getClientChannel(client, serverAddr);
        assertNotNull(clientChannel, "Client channel should be established");

        // Write first partial read
        clientChannel.write(firstRead);
        spinTicks(client, 1);
        spinTicks(server, 1);
        
        // Verify no message received yet (payload incomplete)
        assertEquals(0, received.get(), "No message should be received with partial payload");

        // Write second partial read
        clientChannel.write(secondRead);
        spinTicks(client, 1);
        spinTicks(server, 1);

        // Wait for message processing
        for (int i = 0; i < 100 && received.get() == 0; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(1, received.get(), "Message should be received after complete payload");
    }

    // === Test 3: Multiple Partial Messages in Single Read ===================

    /**
     * Test when multiple messages are partially read in one operation.
     */
    @Test
    public void shouldHandleMultiplePartialMessagesInSingleRead() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        // Create three messages
        Message msg1 = createTestMessage(clientAddr, serverAddr, "msg1");
        Message msg2 = createTestMessage(clientAddr, serverAddr, "msg2");
        Message msg3 = createTestMessage(clientAddr, serverAddr, "msg3");
        
        byte[] encoded1 = new JsonMessageCodec().encode(msg1);
        byte[] encoded2 = new JsonMessageCodec().encode(msg2);
        byte[] encoded3 = new JsonMessageCodec().encode(msg3);

        // Write first two complete messages and first 2 bytes of third length header
        ByteBuffer part1 = ByteBuffer.allocate(4 + encoded1.length + 4 + encoded2.length + 2);
        part1.putInt(encoded1.length);
        part1.put(encoded1);
        part1.putInt(encoded2.length);
        part1.put(encoded2);
        int len3 = encoded3.length;
        part1.put((byte)((len3 >> 24) & 0xFF));
        part1.put((byte)((len3 >> 16) & 0xFF));
        part1.flip();

        // Write last 2 bytes of third length header and payload
        ByteBuffer part2 = ByteBuffer.allocate(2 + encoded3.length);
        part2.put((byte)((len3 >> 8) & 0xFF));
        part2.put((byte)(len3 & 0xFF));
        part2.put(encoded3);
        part2.flip();

        SocketChannel clientChannel = getClientChannel(client, serverAddr);
        assertNotNull(clientChannel, "Client channel should be established");

        // Write partial read (first two complete messages + partial third)
        clientChannel.write(part1);
        spinTicks(client, 1);
        spinTicks(server, 1);

        // Wait for first two messages
        for (int i = 0; i < 100 && received.get() < 2; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }
        assertEquals(2, received.get(), "First two messages should be received");

        // Write remaining part
        clientChannel.write(part2);
        spinTicks(client, 1);
        spinTicks(server, 1);

        // Wait for third message
        for (int i = 0; i < 100 && received.get() < 3; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(3, received.get(), "All three messages should be received");
    }

    // === Test 4: Buffer Management Tests ====================================

    /**
     * Test buffer compaction after partial reads.
     */
    @Test
    public void shouldHandleBufferCompactionAfterPartialReads() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        // Create multiple small messages to trigger buffer compaction
        for (int i = 0; i < 10; i++) {
            Message msg = createTestMessage(clientAddr, serverAddr, "msg-" + i);
            client.send(msg);
        }

        // Flush outbound queue
        for (int i = 0; i < 50; i++) {
            client.tick();
        }

        // Run event loops until all messages arrive
        for (int i = 0; i < 1000 && received.get() < 10; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(10, received.get(), "All messages should be received despite buffer compaction");
    }

    // === Test 5: Partial Write Tests ========================================

    /**
     * Test when a single message write is split across multiple write operations.
     */
    @Test
    public void shouldHandleSingleMessagePartialWrite() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        // Create a large message that will likely cause partial writes
        String largePayload = "x".repeat(5000); // 5KB payload
        Message testMessage = createTestMessage(clientAddr, serverAddr, largePayload);
        client.send(testMessage);

        // Process ticks to handle partial writes
        for (int i = 0; i < 200; i++) {
            client.tick();
            server.tick();
        }

        // Wait for message to be received
        for (int i = 0; i < 1000 && received.get() == 0; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(1, received.get(), "Large message should be received despite partial writes");
    }

    // === Test 6: Error Handling Tests =======================================

    /**
     * Test handling of messages with invalid length headers.
     */
    @Test
    public void shouldHandleInvalidLengthHeaders() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        SocketChannel clientChannel = getClientChannel(client, serverAddr);
        assertNotNull(clientChannel, "Client channel should be established");

        // Send invalid length header (negative length)
        ByteBuffer invalidBuffer = ByteBuffer.allocate(4);
        invalidBuffer.putInt(-1); // Invalid negative length
        invalidBuffer.flip();
        
        clientChannel.write(invalidBuffer);
        spinTicks(client, 1);
        spinTicks(server, 1);

        // Send valid message after invalid one
        Message validMessage = createTestMessage(clientAddr, serverAddr, "valid-message");
        client.send(validMessage);

        // Process ticks
        for (int i = 0; i < 200; i++) {
            client.tick();
            server.tick();
        }

        // Wait for valid message
        for (int i = 0; i < 1000 && received.get() == 0; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(1, received.get(), "Valid message should be received after invalid length header");
    }

    // === Test 7: Edge Case Tests ============================================

    /**
     * Test handling of zero-length messages.
     */
    @Test
    public void shouldHandleZeroLengthMessages() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        server.bind(serverAddr);
        client.bind(clientAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Establish connection
        spinTicks(server, 1);
        spinTicks(client, 1);
        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        // Create zero-length message
        Message zeroMessage = createTestMessage(clientAddr, serverAddr, "");
        client.send(zeroMessage);

        // Process ticks
        for (int i = 0; i < 200; i++) {
            client.tick();
            server.tick();
        }

        // Wait for message
        for (int i = 0; i < 1000 && received.get() == 0; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(1, received.get(), "Zero-length message should be received");
    }

    // === Helper Methods =====================================================

    private SocketChannel getClientChannel(NioNetwork client, NetworkAddress serverAddr) {
        return client.getClientChannel(serverAddr);
    }
} 