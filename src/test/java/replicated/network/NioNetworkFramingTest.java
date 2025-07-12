package replicated.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

    // === Tests =================================================================

    /**
     * Sends multiple small messages rapidly to verify that framing correctly
     * distinguishes message boundaries even when they arrive concat-d.
     */
    @Test
    public void shouldDecodeMultipleMessages() throws Exception {
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

        spinTicks(server, 1);
        spinTicks(client, 1);

        client.establishConnection(serverAddr);
        spinTicks(client, 1);
        spinTicks(server, 5);

        int total = 30;
        for (int i = 0; i < total; i++) {
            byte[] payload = ("msg-" + i).getBytes();
            Message m = new Message(clientAddr, serverAddr, MessageType.PING_REQUEST, payload, UUID.randomUUID().toString());
            client.send(m);
        }

        // Flush outbound queue
        for (int i = 0; i < 50; i++) {
            client.tick();
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
        // Create server with very slow processing (only 5 messages per tick) and low backpressure thresholds
        NioNetwork server = new NioNetwork(new JsonMessageCodec(), 5, 500, 250);
        NioNetwork client = newNetwork();

        int serverPort = freePort();
        int clientPort = freePort();

        NetworkAddress serverAddr = new NetworkAddress("127.0.0.1", serverPort);
        NetworkAddress clientAddr = new NetworkAddress("127.0.0.1", clientPort);

        // Register server
        server.bind(serverAddr);
        final AtomicInteger processedCount = new AtomicInteger(0);
        server.registerMessageHandler((message, context) -> {
            // Simulate slow processing by counting messages
            processedCount.incrementAndGet();
        });

        // Flood the server with messages faster than it can process them
        for (int i = 0; i < 3_000; i++) {
            byte[] payload = ("flood-" + i).getBytes();
            Message m = new Message(clientAddr, serverAddr, MessageType.PING_REQUEST, payload, UUID.randomUUID().toString());
            client.send(m);
        }

        // Aggressively flush client outbound queue
        for (int i = 0; i < 100; i++) {
            client.tick();
        }

        // Run server ticks until inbound queue exceeds backpressure threshold or timeout
        int maxTicks = 1000;
        int queueSize = 0;
        boolean backpressureTriggered = false;
        java.lang.reflect.Field backpressureField;
        try {
            backpressureField = NioNetwork.class.getDeclaredField("backpressureEnabled");
            backpressureField.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < maxTicks && !backpressureTriggered; i++) {
            server.tick();
            // Check if backpressure was triggered
            try {
                backpressureTriggered = backpressureField.getBoolean(server);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            // Print queue size for diagnostics
            java.lang.reflect.Field queueField;
            try {
                queueField = NioNetwork.class.getDeclaredField("inboundMessageQueue");
                queueField.setAccessible(true);
                java.util.concurrent.BlockingQueue<?> inboundQueue = (java.util.concurrent.BlockingQueue<?>) queueField.get(server);
                queueSize = inboundQueue.size();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            if (i % 10 == 0) {
                System.out.println("[TEST] Tick " + i + ": Queue size = " + queueSize + ", Backpressure = " + backpressureTriggered);
            }
        }
        System.out.println("[TEST] Inbound queue size before backpressure assertion: " + queueSize);
        assertTrue(backpressureTriggered, "Back-pressure should be enabled after flood");

        // Continue processing to drain the queue until below low watermark
        int drainTicks = 0;
        final int maxDrainTicks = 2000;
        while (queueSize > 250 && drainTicks < maxDrainTicks) {
            server.tick();
            drainTicks++;
            // Update queue size
            try {
                java.lang.reflect.Field queueField = NioNetwork.class.getDeclaredField("inboundMessageQueue");
                queueField.setAccessible(true);
                java.util.concurrent.BlockingQueue<?> inboundQueue = (java.util.concurrent.BlockingQueue<?>) queueField.get(server);
                queueSize = inboundQueue.size();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        // Print queue size and backpressure state after draining
        boolean finalBackpressure = false;
        try {
            finalBackpressure = backpressureField.getBoolean(server);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        System.out.println("[TEST] After draining: Queue size = " + queueSize + ", Backpressure = " + finalBackpressure + ", Ticks = " + drainTicks);

        // Check if backpressure was released
        assertFalse(finalBackpressure, "Back-pressure should be released after queue drains");
    }
} 