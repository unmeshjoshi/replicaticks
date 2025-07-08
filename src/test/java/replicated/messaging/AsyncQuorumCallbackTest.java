package replicated.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import replicated.future.ListenableFuture;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsyncQuorumCallbackTest {

    private AsyncQuorumCallback<String> callback;
    private NetworkAddress node1;
    private NetworkAddress node2;
    private NetworkAddress node3;

    @BeforeEach
    void setUp() {
        node1 = new NetworkAddress("127.0.0.1", 8081);
        node2 = new NetworkAddress("127.0.0.1", 8082);
        node3 = new NetworkAddress("127.0.0.1", 8083);
    }

    @Test
    void shouldCompleteWithMajorityQuorum() {
        // Given - 3 total responses, need 2 for majority
        callback = new AsyncQuorumCallback<>(3);
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive 2 responses (majority)
        callback.onResponse("response1", node1);
        callback.onResponse("response2", node2);

        // Then - should complete successfully
        assertNotNull(result[0]);
        assertEquals(2, result[0].size());
        assertEquals("response1", result[0].get(node1));
        assertEquals("response2", result[0].get(node2));
        assertNull(error[0]);
    }

    @Test
    void shouldNotCompleteWithoutMajority() {
        // Given - 3 total responses, need 2 for majority
        callback = new AsyncQuorumCallback<>(3);
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive only 1 response (not majority)
        callback.onResponse("response1", node1);

        // Then - should not complete
        assertNull(result[0]);
        assertNull(error[0]);
    }

    @Test
    void shouldFailWhenAllResponsesReceivedWithoutQuorum() {
        // Given - 3 total responses, need 2 for majority
        callback = new AsyncQuorumCallback<>(3, (responses) -> false);
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive all 3 responses but none satisfy quorum condition
        callback.onResponse("response1", node1);
        callback.onResponse("response2", node2);
        callback.onResponse("response3", node3);

        // Wait for the future to complete (max 100ms)
        long start = System.currentTimeMillis();
        while (error[0] == null && result[0] == null && System.currentTimeMillis() - start < 100) {
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
        System.out.println("DEBUG: result=" + result[0] + ", error=" + error[0]);
        // Then - should fail with quorum not met error
        assertNotNull(error[0]);
        assertTrue(error[0].getMessage().contains("Quorum condition not met"));
        assertNull(result[0]);
    }

    @Test
    void shouldCompleteWithCustomSuccessCondition() {
        // Given - custom success condition that only accepts "success" responses
        callback = new AsyncQuorumCallback<>(3, response -> "success".equals(response));
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive 2 "success" responses (majority)
        callback.onResponse("success", node1);
        callback.onResponse("success", node2);

        // Then - should complete successfully
        assertNotNull(result[0]);
        assertEquals(2, result[0].size());
        assertEquals("success", result[0].get(node1));
        assertEquals("success", result[0].get(node2));
        assertNull(error[0]);
    }

    @Test
    void shouldFailWithCustomSuccessConditionWhenNotEnoughSuccesses() {
        // Given - custom success condition that only accepts "success" responses
        callback = new AsyncQuorumCallback<>(3, response -> "success".equals(response));
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive 1 "success" and 2 "failure" responses
        callback.onResponse("success", node1);
        callback.onResponse("failure", node2);
        callback.onResponse("failure", node3);

        // Then - should fail because only 1 success (not majority of 2)
        assertNull(result[0]);
        assertNotNull(error[0]);
        assertTrue(error[0].getMessage().contains("Quorum condition not met"));
    }

    @Test
    void shouldHandleErrors() {
        // Given - 3 total responses, need 2 for majority
        callback = new AsyncQuorumCallback<>(3);
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive 1 response and 2 errors
        callback.onResponse("response1", node1);
        callback.onError(new RuntimeException("Node 2 failed"));
        callback.onError(new RuntimeException("Node 3 failed"));

        // Then - should fail because only 1 success (not majority of 2)
        assertNull(result[0]);
        assertNotNull(error[0]);
        assertTrue(error[0].getMessage().contains("Quorum condition not met"));
    }

    @Test
    void shouldHandleMixedResponsesAndErrors() {
        // Given - 3 total responses, need 2 for majority
        callback = new AsyncQuorumCallback<>(3);
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive 2 responses and 1 error
        callback.onResponse("response1", node1);
        callback.onResponse("response2", node2);
        callback.onError(new RuntimeException("Node 3 failed"));

        // Then - should complete successfully (2 responses = majority)
        assertNotNull(result[0]);
        assertEquals(2, result[0].size());
        assertEquals("response1", result[0].get(node1));
        assertEquals("response2", result[0].get(node2));
        assertNull(error[0]);
    }

    @Test
    void shouldThrowExceptionForInvalidTotalResponses() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new AsyncQuorumCallback<>(0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new AsyncQuorumCallback<>(-1);
        });
    }

    @Test
    void shouldCalculateCorrectMajorityForEvenNumbers() {
        // Given - 4 total responses, need 3 for majority (4/2 + 1 = 3)
        callback = new AsyncQuorumCallback<>(4);
        final Map<NetworkAddress, String>[] result = new Map[1];
        final Throwable[] error = new Throwable[1];

        ListenableFuture<Map<NetworkAddress, String>> future = callback.getQuorumFuture();
        future.onSuccess(response -> result[0] = response);
        future.onFailure(throwable -> error[0] = throwable);

        // When - receive 3 responses (majority of 4)
        callback.onResponse("response1", node1);
        callback.onResponse("response2", node2);
        callback.onResponse("response3", node3);

        // Then - should complete successfully
        assertNotNull(result[0]);
        assertEquals(3, result[0].size());
        assertNull(error[0]);
    }
} 