package replicated.messaging;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * System clock abstraction for timing operations.
 * Provides monotonic time for request tracking and timeout management.
 */
public class SystemClock {
    private Duration clockSkew = Duration.of(0, ChronoUnit.MILLIS);

    /**
     * Gets the current time in nanoseconds.
     * This is guaranteed to be monotonic.
     * 
     * @return the current time in nanoseconds
     */
    public long nanoTime() {
        return System.nanoTime() + clockSkew.toNanos();
    }

    /**
     * Gets the current time in milliseconds.
     * Note: This is not guaranteed to be monotonic.
     * 
     * @return the current time in milliseconds
     */
    public long now() {
        return System.currentTimeMillis() + clockSkew.toMillis();
    }

    /**
     * Adds clock skew for testing purposes.
     * 
     * @param clockSkew the clock skew to add
     */
    public void addClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }
} 