package replicated.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkConfigTest {
    
    @Test
    void shouldCreateDefaultConfig() {
        NetworkConfig config = NetworkConfig.defaults();
        
        assertEquals(1000, config.maxInboundPerTick());
        assertEquals(10000, config.backpressureHighWatermark());
        assertEquals(5000, config.backpressureLowWatermark());
    }
    
    @Test
    void shouldCreateConfigWithBuilder() {
        NetworkConfig config = NetworkConfig.builder()
                .maxInboundPerTick(500)
                .backpressureHighWatermark(5000)
                .backpressureLowWatermark(2000)
                .build();
        
        assertEquals(500, config.maxInboundPerTick());
        assertEquals(5000, config.backpressureHighWatermark());
        assertEquals(2000, config.backpressureLowWatermark());
    }
    
    @Test
    void shouldValidateMaxInboundPerTick() {
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .maxInboundPerTick(0)
                    .build();
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .maxInboundPerTick(-1)
                    .build();
        });
    }
    
    @Test
    void shouldValidateBackpressureHighWatermark() {
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .backpressureHighWatermark(0)
                    .build();
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .backpressureHighWatermark(-1)
                    .build();
        });
    }
    
    @Test
    void shouldValidateBackpressureLowWatermark() {
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .backpressureLowWatermark(0)
                    .build();
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .backpressureLowWatermark(-1)
                    .build();
        });
    }
    
    @Test
    void shouldValidateBackpressureWatermarkRelationship() {
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .backpressureHighWatermark(1000)
                    .backpressureLowWatermark(1000) // equal to high watermark
                    .build();
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkConfig.builder()
                    .backpressureHighWatermark(1000)
                    .backpressureLowWatermark(2000) // greater than high watermark
                    .build();
        });
    }
    
    @Test
    void shouldHaveMeaningfulToString() {
        NetworkConfig config = NetworkConfig.builder()
                .maxInboundPerTick(500)
                .backpressureHighWatermark(5000)
                .backpressureLowWatermark(2000)
                .build();
        
        String toString = config.toString();
        assertTrue(toString.contains("maxInboundPerTick=500"));
        assertTrue(toString.contains("backpressureHighWatermark=5000"));
        assertTrue(toString.contains("backpressureLowWatermark=2000"));
    }
} 