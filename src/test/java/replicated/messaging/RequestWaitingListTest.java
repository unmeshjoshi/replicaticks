package replicated.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class RequestWaitingListTest {

    private RequestWaitingList<String, String> requestWaitingList;

    @BeforeEach
    void setUp() {
        requestWaitingList = new RequestWaitingList<>(10); // 10 ticks timeout
    }

    @Test
    void shouldAddAndHandleResponse() {
        // Given
        String correlationId = "test-123";
        final String[] receivedResponse = {null};
        final Throwable[] receivedError = {null};

        RequestCallback<String> callback = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                receivedResponse[0] = response;
            }

            @Override
            public void onError(Exception error) {
                receivedError[0] = error;
            }
        };

        // When
        requestWaitingList.add(correlationId, callback);
        requestWaitingList.handleResponse(correlationId, "success", new NetworkAddress("127.0.0.1", 8081));

        // Then
        assertEquals("success", receivedResponse[0]);
        assertNull(receivedError[0]);
    }

    @Test
    void shouldHandleError() {
        // Given
        String correlationId = "test-123";
        final String[] receivedResponse = {null};
        final Throwable[] receivedError = {null};

        RequestCallback<String> callback = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                receivedResponse[0] = response;
            }

            @Override
            public void onError(Exception error) {
                receivedError[0] = error;
            }
        };

        // When
        requestWaitingList.add(correlationId, callback);
        requestWaitingList.handleError(correlationId, new RuntimeException("Test error"));

        // Then
        assertNull(receivedResponse[0]);
        assertNotNull(receivedError[0]);
        assertEquals("Test error", receivedError[0].getMessage());
    }

    @Test
    void shouldRemoveRequestAfterHandling() {
        // Given
        String correlationId = "test-123";
        final String[] receivedResponse = {null};

        RequestCallback<String> callback = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                receivedResponse[0] = response;
            }

            @Override
            public void onError(Exception error) {
                // Not used in this test
            }
        };

        // When
        requestWaitingList.add(correlationId, callback);
        requestWaitingList.handleResponse(correlationId, "success", new NetworkAddress("127.0.0.1", 8081));

        // Then - request should be removed after handling
        assertEquals(0, requestWaitingList.size());
    }

    @Test
    void shouldExpireRequestsAfterTimeout() {
        // Given
        String correlationId = "test-123";
        final String[] receivedResponse = {null};
        final Throwable[] receivedError = {null};

        RequestCallback<String> callback = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                receivedResponse[0] = response;
            }

            @Override
            public void onError(Exception error) {
                receivedError[0] = error;
            }
        };

        // When
        requestWaitingList.add(correlationId, callback);
        
        // Simulate time passing beyond expiration (11 ticks > 10 tick timeout)
        for (int i = 0; i < 11; i++) {
            requestWaitingList.tick();
        }

        // Then - request should be expired and removed
        assertEquals(0, requestWaitingList.size());
        assertNull(receivedResponse[0]);
        assertNotNull(receivedError[0]);
        assertTrue(receivedError[0] instanceof TimeoutException);
    }

    @Test
    void shouldNotExpireRequestsBeforeTimeout() {
        // Given
        String correlationId = "test-123";
        final String[] receivedResponse = {null};
        final Throwable[] receivedError = {null};

        RequestCallback<String> callback = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                receivedResponse[0] = response;
            }

            @Override
            public void onError(Exception error) {
                receivedError[0] = error;
            }
        };

        // When
        requestWaitingList.add(correlationId, callback);
        
        // Simulate time passing but not beyond expiration (5 ticks < 10 tick timeout)
        for (int i = 0; i < 5; i++) {
            requestWaitingList.tick();
        }

        // Then - request should still be pending
        assertEquals(1, requestWaitingList.size());
        assertNull(receivedResponse[0]);
        assertNull(receivedError[0]);
    }

    @Test
    void shouldHandleMultipleRequests() {
        // Given
        String correlationId1 = "test-1";
        String correlationId2 = "test-2";
        final String[] response1 = {null};
        final String[] response2 = {null};

        RequestCallback<String> callback1 = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                response1[0] = response;
            }

            @Override
            public void onError(Exception error) {
                // Not used in this test
            }
        };

        RequestCallback<String> callback2 = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                response2[0] = response;
            }

            @Override
            public void onError(Exception error) {
                // Not used in this test
            }
        };

        // When
        requestWaitingList.add(correlationId1, callback1);
        requestWaitingList.add(correlationId2, callback2);
        
        requestWaitingList.handleResponse(correlationId1, "response1", new NetworkAddress("127.0.0.1", 8081));
        requestWaitingList.handleResponse(correlationId2, "response2", new NetworkAddress("127.0.0.1", 8082));

        // Then
        assertEquals("response1", response1[0]);
        assertEquals("response2", response2[0]);
        assertEquals(0, requestWaitingList.size());
    }

    @Test
    void shouldReturnEmptyListWhenNoExpiredRequests() {
        // Given
        String correlationId = "test-123";
        RequestCallback<String> callback = new RequestCallback<String>() {
            @Override
            public void onResponse(String response, NetworkAddress fromNode) {
                // Not used in this test
            }

            @Override
            public void onError(Exception error) {
                // Not used in this test
            }
        };

        // When
        requestWaitingList.add(correlationId, callback);
        requestWaitingList.tick();

        // Then
        assertEquals(1, requestWaitingList.size());
    }

    @Test
    void shouldHandleNonExistentRequestGracefully() {
        // Given
        String nonExistentId = "non-existent";

        // When & Then - should not throw exception
        assertDoesNotThrow(() -> {
            requestWaitingList.handleResponse(nonExistentId, "response", new NetworkAddress("127.0.0.1", 8081));
        });

        assertDoesNotThrow(() -> {
            requestWaitingList.handleError(nonExistentId, new RuntimeException("error"));
        });
    }
} 