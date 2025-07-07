package replicated.simulation;

/**
 * Centralized tick manager that serves as the single source of truth
 * for simulation time across all components.
 * 
 * This follows TigerBeetle's pattern of having one authoritative
 * tick counter that all components reference.
 */
public final class TickManager {
    
    private long currentTick = 0;
    
    /**
     * Advances the simulation by one tick and returns the new tick value.
     * This is the ONLY place where ticks should be incremented.
     * 
     * @return the new current tick value
     */
    public long tick() {
        return ++currentTick;
    }
    
    /**
     * Gets the current tick value without advancing it.
     * 
     * @return the current tick value
     */
    public long getCurrentTick() {
        return currentTick;
    }
    
    /**
     * Resets the tick counter to 0.
     * Useful for test scenarios.
     */
    public void reset() {
        currentTick = 0;
    }
    
    /**
     * Sets the tick counter to a specific value.
     * Useful for test scenarios.
     * 
     * @param tick the tick value to set
     */
    public void setTick(long tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("Tick cannot be negative");
        }
        this.currentTick = tick;
    }
} 