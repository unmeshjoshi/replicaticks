package replicated.network;

/**
 * Configuration for network behavior including backpressure and performance tuning.
 * Immutable configuration object with builder pattern support.
 */
public final class NetworkConfig {
    
    // === Performance tuning ===
    private final int maxInboundPerTick;
    
    // === Backpressure tuning ===
    private final int backpressureHighWatermark;
    private final int backpressureLowWatermark;
    
    private NetworkConfig(Builder builder) {
        this.maxInboundPerTick = builder.maxInboundPerTick;
        this.backpressureHighWatermark = builder.backpressureHighWatermark;
        this.backpressureLowWatermark = builder.backpressureLowWatermark;
        
        validate();
    }
    
    private void validate() {
        if (maxInboundPerTick <= 0) {
            throw new IllegalArgumentException("maxInboundPerTick must be positive");
        }
        if (backpressureHighWatermark <= 0) {
            throw new IllegalArgumentException("backpressureHighWatermark must be positive");
        }
        if (backpressureLowWatermark <= 0) {
            throw new IllegalArgumentException("backpressureLowWatermark must be positive");
        }
        if (backpressureLowWatermark >= backpressureHighWatermark) {
            throw new IllegalArgumentException("backpressureLowWatermark must be less than backpressureHighWatermark");
        }
    }
    
    public int maxInboundPerTick() {
        return maxInboundPerTick;
    }
    
    public int backpressureHighWatermark() {
        return backpressureHighWatermark;
    }
    
    public int backpressureLowWatermark() {
        return backpressureLowWatermark;
    }
    
    /**
     * Creates a builder with default values.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a configuration with default values.
     */
    public static NetworkConfig defaults() {
        return builder().build();
    }
    
    @Override
    public String toString() {
        return String.format("NetworkConfig{maxInboundPerTick=%d, backpressureHighWatermark=%d, backpressureLowWatermark=%d}",
                maxInboundPerTick, backpressureHighWatermark, backpressureLowWatermark);
    }
    
    /**
     * Builder for NetworkConfig with fluent API.
     */
    public static final class Builder {
        private int maxInboundPerTick = 1000; // safeguard to avoid starving other work per tick
        private int backpressureHighWatermark = 10000;
        private int backpressureLowWatermark = 5000;
        
        public Builder maxInboundPerTick(int maxInboundPerTick) {
            this.maxInboundPerTick = maxInboundPerTick;
            return this;
        }
        
        public Builder backpressureHighWatermark(int backpressureHighWatermark) {
            this.backpressureHighWatermark = backpressureHighWatermark;
            return this;
        }
        
        public Builder backpressureLowWatermark(int backpressureLowWatermark) {
            this.backpressureLowWatermark = backpressureLowWatermark;
            return this;
        }
        
        public NetworkConfig build() {
            return new NetworkConfig(this);
        }
    }
} 