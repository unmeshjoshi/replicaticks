package replicated.messaging;

import replicated.future.ListenableFuture;

/**
 * Client-specific callback for handling request responses.
 * This callback completes a ListenableFuture when a response is received.
 * 
 * @param <T> the type of the response
 */
public class ClientRequestCallback<T> implements RequestCallback<T> {
    private final ListenableFuture<T> future;

    /**
     * Creates a new ClientRequestCallback.
     * 
     * @param future the future to complete when the response is received
     */
    public ClientRequestCallback(ListenableFuture<T> future) {
        this.future = future;
    }

    @Override
    public void onResponse(T response, NetworkAddress fromNode) {
        future.complete(response);
    }

    @Override
    public void onError(Exception error) {
        future.fail(error);
    }

    /**
     * Gets the future associated with this callback.
     * 
     * @return the future
     */
    public ListenableFuture<T> getFuture() {
        return future;
    }
} 