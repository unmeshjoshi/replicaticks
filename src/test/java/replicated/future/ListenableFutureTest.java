package replicated.future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class ListenableFutureTest {
    
    private ListenableFuture<String> future;
    
    @BeforeEach
    void setUp() {
        future = new ListenableFuture<>();
    }
    
    @Test
    void shouldStartInPendingState() {
        // Given a new future
        // When created
        // Then it should be in pending state
        assertTrue(future.isPending());
        assertFalse(future.isCompleted());
        assertFalse(future.isFailed());
    }
    
    @Test
    void shouldCompleteSuccessfully() {
        // Given a pending future
        String result = "success";
        
        // When completed with a value
        future.complete(result);
        
        // Then it should be in completed state
        assertFalse(future.isPending());
        assertTrue(future.isCompleted());
        assertFalse(future.isFailed());
        assertEquals(result, future.getResult());
    }
    
    @Test
    void shouldFailWithException() {
        // Given a pending future
        RuntimeException error = new RuntimeException("test error");
        
        // When failed with an exception
        future.fail(error);
        
        // Then it should be in failed state
        assertFalse(future.isPending());
        assertFalse(future.isCompleted());
        assertTrue(future.isFailed());
        assertEquals(error, future.getException());
    }
    
    @Test
    void shouldNotAllowDoubleCompletion() {
        // Given a completed future
        future.complete("first");
        
        // When trying to complete again
        // Then should throw exception
        assertThrows(IllegalStateException.class, () -> future.complete("second"));
    }
    
    @Test
    void shouldNotAllowCompletionAfterFailure() {
        // Given a failed future
        future.fail(new RuntimeException("error"));
        
        // When trying to complete
        // Then should throw exception
        assertThrows(IllegalStateException.class, () -> future.complete("result"));
    }
    
    @Test
    void shouldNotAllowFailureAfterCompletion() {
        // Given a completed future
        future.complete("result");
        
        // When trying to fail
        // Then should throw exception
        assertThrows(IllegalStateException.class, () -> future.fail(new RuntimeException("error")));
    }
    
    @Test
    void shouldInvokeSuccessCallbackImmediatelyIfAlreadyCompleted() {
        // Given a completed future
        String result = "test result";
        future.complete(result);
        
        // When adding a success callback
        AtomicReference<String> callbackResult = new AtomicReference<>();
        future.onSuccess(callbackResult::set);
        
        // Then callback should be invoked immediately
        assertEquals(result, callbackResult.get());
    }
    
    @Test
    void shouldInvokeSuccessCallbackWhenCompletedLater() {
        // Given a pending future with callback
        AtomicReference<String> callbackResult = new AtomicReference<>();
        future.onSuccess(callbackResult::set);
        
        // When completed later
        String result = "delayed result";
        future.complete(result);
        
        // Then callback should be invoked
        assertEquals(result, callbackResult.get());
    }
    
    @Test
    void shouldNotInvokeSuccessCallbackOnFailure() {
        // Given a future with success callback
        AtomicInteger callbackCount = new AtomicInteger(0);
        future.onSuccess(result -> callbackCount.incrementAndGet());
        
        // When failed
        future.fail(new RuntimeException("error"));
        
        // Then success callback should not be invoked
        assertEquals(0, callbackCount.get());
    }
    
    @Test
    void shouldInvokeFailureCallbackImmediatelyIfAlreadyFailed() {
        // Given a failed future
        RuntimeException error = new RuntimeException("test error");
        future.fail(error);
        
        // When adding a failure callback
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        future.onFailure(callbackError::set);
        
        // Then callback should be invoked immediately
        assertEquals(error, callbackError.get());
    }
    
    @Test
    void shouldInvokeFailureCallbackWhenFailedLater() {
        // Given a pending future with callback
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        future.onFailure(callbackError::set);
        
        // When failed later
        RuntimeException error = new RuntimeException("delayed error");
        future.fail(error);
        
        // Then callback should be invoked
        assertEquals(error, callbackError.get());
    }
    
    @Test
    void shouldNotInvokeFailureCallbackOnSuccess() {
        // Given a future with failure callback
        AtomicInteger callbackCount = new AtomicInteger(0);
        future.onFailure(error -> callbackCount.incrementAndGet());
        
        // When completed successfully
        future.complete("success");
        
        // Then failure callback should not be invoked
        assertEquals(0, callbackCount.get());
    }
    
    @Test
    void shouldSupportMultipleSuccessCallbacks() {
        // Given a future with multiple success callbacks
        AtomicReference<String> callback1Result = new AtomicReference<>();
        AtomicReference<String> callback2Result = new AtomicReference<>();
        
        future.onSuccess(callback1Result::set);
        future.onSuccess(callback2Result::set);
        
        // When completed
        String result = "shared result";
        future.complete(result);
        
        // Then all callbacks should be invoked
        assertEquals(result, callback1Result.get());
        assertEquals(result, callback2Result.get());
    }
    
    @Test
    void shouldSupportMultipleFailureCallbacks() {
        // Given a future with multiple failure callbacks
        AtomicReference<Throwable> callback1Error = new AtomicReference<>();
        AtomicReference<Throwable> callback2Error = new AtomicReference<>();
        
        future.onFailure(callback1Error::set);
        future.onFailure(callback2Error::set);
        
        // When failed
        RuntimeException error = new RuntimeException("shared error");
        future.fail(error);
        
        // Then all callbacks should be invoked
        assertEquals(error, callback1Error.get());
        assertEquals(error, callback2Error.get());
    }
    
    @Test
    void shouldThrowWhenAccessingResultFromPendingFuture() {
        // Given a pending future
        // When trying to access result
        // Then should throw exception
        assertThrows(IllegalStateException.class, () -> future.getResult());
    }
    
    @Test
    void shouldThrowWhenAccessingResultFromFailedFuture() {
        // Given a failed future
        future.fail(new RuntimeException("error"));
        
        // When trying to access result
        // Then should throw exception
        assertThrows(IllegalStateException.class, () -> future.getResult());
    }
    
    @Test
    void shouldThrowWhenAccessingExceptionFromPendingFuture() {
        // Given a pending future
        // When trying to access exception
        // Then should throw exception
        assertThrows(IllegalStateException.class, () -> future.getException());
    }
    
    @Test
    void shouldThrowWhenAccessingExceptionFromSuccessfulFuture() {
        // Given a successful future
        future.complete("success");
        
        // When trying to access exception
        // Then should throw exception
        assertThrows(IllegalStateException.class, () -> future.getException());
    }
} 