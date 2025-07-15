package replicated.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import replicated.TestUtils;
import replicated.messaging.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests covering the length-prefixed framing logic and the
 * back-pressure mechanism recently added to {@link NioNetwork}.
 */
public class NioNetworkFramingTest {

    private final List<NioNetwork> resources = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NioNetwork n : resources) {
            try { n.close(); } catch (Exception ignored) {}
        }
        resources.clear();
    }

    // === Helpers ==============================================================
    private static void spinTicks(NioNetwork n, int count) {
        for (int i = 0; i < count; i++) n.tick();
    }

    private NioNetwork newNetwork() {
        NioNetwork n = new NioNetwork(new JsonMessageCodec());
        resources.add(n);
        return n;
    }

    // === Tests =================================================================

    /**
     * Sends multiple small messages rapidly to verify that framing correctly
     * distinguishes message boundaries even when they arrive concatenated.
     */
    @Test
    public void shouldDecodeMultipleMessages() throws Exception {
        NioNetwork server = newNetwork();
        NioNetwork client = newNetwork();

        NetworkAddress serverAddr = TestUtils.randomAddress();
        server.bind(serverAddr);

        AtomicInteger received = new AtomicInteger();
        server.registerMessageHandler((msg, ctx) -> received.incrementAndGet());

        // Allow server to start listening
        spinTicks(server, 1);

        int total = 30;
        for (int i = 0; i < total; i++) {
            byte[] payload = ("msg-" + i).getBytes();
            Message m = Message.unboundMessage(serverAddr, MessageType.PING_REQUEST, payload, UUID.randomUUID().toString());
            client.send(m);
        }

        // Flush outbound queue and allow connections to establish
        for (int i = 0; i < 50; i++) {
            client.tick();
            server.tick();
        }

        // Run event loops until all messages arrive or timeout
        for (int i = 0; i < 5000 && received.get() < total; i++) {
            spinTicks(client, 1);
            spinTicks(server, 1);
        }

        assertEquals(total, received.get(), "Server should decode all framed messages");
    }

    /**
     * Stress test for back-pressure mechanism: flood the server with messages
     * faster than it can process them, verify back-pressure engages, then
     * verify it releases when the queue drains.
     */
    @Test
    public void shouldApplyAndReleaseBackpressure() throws Exception {
        // Create server with very slow processing (only 1 message per tick) and very low backpressure thresholds
        NioNetwork server = new NioNetwork(new JsonMessageCodec(), 1, 2, 1);
        NioNetwork client = newNetwork();

        NetworkAddress serverAddr = TestUtils.randomAddress();

        // Register server
        server.bind(serverAddr);

        // Allow server to start listening
        spinTicks(server, 1);

        // Flood the server with 10 messages before ticking the server
        int floodCount = 10;
        for (int i = 0; i < floodCount; i++) {
            byte[] payload = ("msg-" + i).getBytes();
            Message m = Message.unboundMessage(serverAddr, MessageType.PING_REQUEST, payload, UUID.randomUUID().toString());
            client.send(m);
        }
        
        // Flush outbound queue and allow connections to establish
        spinTicks(client, 10);
        spinTicks(server, 5);

        // Backpressure should be enabled after the burst
        assertTrue(server.isBackpressureEnabled(), "Back-pressure should be enabled after flood");

        // Now register the handler and drain the queue
        final AtomicInteger processedCount = new AtomicInteger(0);
        server.registerMessageHandler((msg, ctx) -> processedCount.incrementAndGet());
        int ticks = 0;
        while (server.isBackpressureEnabled() && ticks < 20) {
            server.tick();
            ticks++;
        }
        assertFalse(server.isBackpressureEnabled(), "Back-pressure should be released after draining");
    }
} 