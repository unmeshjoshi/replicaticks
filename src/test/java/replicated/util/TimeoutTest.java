package replicated.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Timeout class that manages its own internal tick counter.
 */
class TimeoutTest {
    
    private Timeout timeout;
    
    @BeforeEach
    void setUp() {
        timeout = new Timeout("test-timeout", 5);
    }
    
    @Test
    void shouldCreateTimeoutWithValidParameters() {
        // Given/When
        Timeout timeout = new Timeout("test", 10);
        
        // Then
        assertEquals("test", timeout.getName());
        assertEquals(10, timeout.getDurationTicks());
        assertEquals(0, timeout.getTicks());
        assertFalse(timeout.isTicking());
    }
    
    @Test
    void shouldThrowExceptionForNullName() {
        assertThrows(IllegalArgumentException.class, () ->
            new Timeout(null, 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForEmptyName() {
        assertThrows(IllegalArgumentException.class, () ->
            new Timeout("", 10)
        );
    }
    
    @Test
    void shouldThrowExceptionForZeroDuration() {
        assertThrows(IllegalArgumentException.class, () ->
            new Timeout("test", 0)
        );
    }
    
    @Test
    void shouldThrowExceptionForNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () ->
            new Timeout("test", -1)
        );
    }
    
    @Test
    void shouldNotFireWhenNotStarted() {
        // Given - timeout not started
        
        // When - tick multiple times
        for (int i = 0; i < 10; i++) {
            timeout.tick();
        }
        
        // Then
        assertFalse(timeout.fired());
        assertFalse(timeout.isTicking());
    }
    
    @Test
    void shouldFireAfterDurationTicks() {
        // Given
        timeout.start();
        
        // When - tick exactly duration times
        for (int i = 0; i < 5; i++) {
            timeout.tick();
        }
        
        // Then
        assertTrue(timeout.fired());
        assertTrue(timeout.isTicking());
    }
    
    @Test
    void shouldNotFireBeforeDurationTicks() {
        // Given
        timeout.start();
        
        // When - tick less than duration times
        for (int i = 0; i < 4; i++) {
            timeout.tick();
        }
        
        // Then
        assertFalse(timeout.fired());
        assertTrue(timeout.isTicking());
    }
    
    @Test
    void shouldFireAfterMoreThanDurationTicks() {
        // Given
        timeout.start();
        
        // When - tick more than duration times
        for (int i = 0; i < 10; i++) {
            timeout.tick();
        }
        
        // Then
        assertTrue(timeout.fired());
        assertTrue(timeout.isTicking());
    }
    
    @Test
    void shouldResetTimeout() {
        // Given
        timeout.start();
        
        // When - tick to almost timeout, then reset
        for (int i = 0; i < 4; i++) {
            timeout.tick();
        }
        assertFalse(timeout.fired());
        
        timeout.reset();
        
        // Then - should not fire immediately after reset
        assertFalse(timeout.fired());
        
        // When - tick again
        for (int i = 0; i < 4; i++) {
            timeout.tick();
        }
        
        // Then - should not fire yet (reset moved start point)
        assertFalse(timeout.fired());
        
        // When - tick one more time
        timeout.tick();
        
        // Then - should fire now
        assertTrue(timeout.fired());
    }
    
    @Test
    void shouldStopTimeout() {
        // Given
        timeout.start();
        
        // When - tick and then stop
        timeout.tick();
        timeout.stop();
        
        // Then
        assertFalse(timeout.isTicking());
        assertFalse(timeout.fired());
        
        // When - tick more times
        for (int i = 0; i < 10; i++) {
            timeout.tick();
        }
        
        // Then - should still not fire
        assertFalse(timeout.fired());
    }
    
    @Test
    void shouldCalculateRemainingTicksCorrectly() {
        // Given
        timeout.start();
        
        // When - tick some times
        timeout.tick();
        timeout.tick();
        
        // Then
        assertEquals(3, timeout.getRemainingTicks());
        
        // When - tick more
        timeout.tick();
        timeout.tick();
        
        // Then
        assertEquals(1, timeout.getRemainingTicks());
        
        // When - tick one more
        timeout.tick();
        
        // Then
        assertEquals(0, timeout.getRemainingTicks());
    }
    
    @Test
    void shouldReturnZeroRemainingWhenNotTicking() {
        // Given - timeout not started
        
        // When/Then
        assertEquals(0, timeout.getRemainingTicks());
        
        // Given - timeout stopped
        timeout.start();
        timeout.stop();
        
        // When/Then
        assertEquals(0, timeout.getRemainingTicks());
    }
    
    @Test
    void shouldTrackTicksCorrectly() {
        // Given
        assertEquals(0, timeout.getTicks());
        
        // When
        timeout.tick();
        timeout.tick();
        timeout.tick();
        
        // Then
        assertEquals(3, timeout.getTicks());
    }
    
    @Test
    void shouldProvideUsefulToString() {
        // Given
        timeout.start();
        
        // When
        String result = timeout.toString();
        
        // Then
        assertTrue(result.contains("test-timeout"));
        assertTrue(result.contains("duration=5"));
        assertTrue(result.contains("ticking=true"));
        assertTrue(result.contains("remaining="));
    }
} 