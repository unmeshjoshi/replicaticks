package replicated.algorithms.paxos;

import java.util.Arrays;

/**
 * Client request to propose a value via Paxos consensus.
 */
public final class ProposeRequest {
    private byte[] value;
    private String correlationId;
    
    // Default constructor for JSON deserialization
    public ProposeRequest() {
    }
    
    public ProposeRequest(byte[] value, String correlationId) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (correlationId == null || correlationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Correlation ID cannot be null or empty");
        }
        this.value = value.clone(); // Defensive copy
        this.correlationId = correlationId;
    }
    
    public byte[] getValue() {
        return value.clone(); // Defensive copy
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProposeRequest)) return false;
        ProposeRequest other = (ProposeRequest) obj;
        return Arrays.equals(value, other.value) && correlationId.equals(other.correlationId);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(value) * 31 + correlationId.hashCode();
    }
    
    @Override
    public String toString() {
        return "ProposeRequest{" +
                "value=" + new String(value) +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
} 