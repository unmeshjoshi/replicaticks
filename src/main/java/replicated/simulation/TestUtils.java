package replicated.simulation;

import java.util.function.Supplier;

/**
 * Test utilities for simulation testing scenarios.
 * Provides runUntil functionality for waiting for conditions in tests.
 */
public class TestUtils {
    
    /**
     * Runs the simulation until a condition is met or timeout occurs.
     * This method advances the simulation by calling tick() repeatedly until
     * the condition returns true or the timeout is reached.
     * 
     * @param condition the condition to wait for
     * @param timeoutMs timeout in milliseconds
     * @param driver the simulation driver to tick
     * @throws RuntimeException if timeout is reached before condition is met
     */
    public static void runUntil(Supplier<Boolean> condition, long timeoutMs, SimulationDriver driver) {
        long startTime = System.currentTimeMillis();
        while (!condition.get()) {
            driver.tick();
            
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new RuntimeException("Timeout waiting for condition to be met after " + timeoutMs + "ms");
            }
            
            // Small yield to prevent busy waiting
            Thread.yield();
        }
    }
    
    /**
     * Convenience method with default timeout of 10 seconds.
     * 
     * @param condition the condition to wait for
     * @param driver the simulation driver to tick
     */
    public static void runUntil(Supplier<Boolean> condition, SimulationDriver driver) {
        runUntil(condition, 10000, driver);
    }
} 