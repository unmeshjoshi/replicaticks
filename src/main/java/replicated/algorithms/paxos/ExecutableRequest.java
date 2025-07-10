package replicated.algorithms.paxos;

import java.util.Arrays;

/**
 * Wrapper for executable requests that can be proposed and executed via Paxos.
 * Encapsulates arbitrary byte[] requests (like IncrementCounter) for consensus.
 */
public final class ExecutableRequest {
    private final byte[] requestData;
    private final String requestId;
    
    public ExecutableRequest(byte[] requestData, String requestId) {
        if (requestData == null) {
            throw new IllegalArgumentException("Request data cannot be null");
        }
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("Request ID cannot be null or empty");
        }
        this.requestData = requestData.clone(); // Defensive copy
        this.requestId = requestId;
    }
    
    /**
     * Returns the request data to be executed.
     */
    public byte[] getRequestData() {
        return requestData.clone(); // Defensive copy
    }
    
    /**
     * Returns the unique request identifier.
     */
    public String getRequestId() {
        return requestId;
    }
    
    /**
     * Executes this request and returns the result.
     * For now, this is a simple demonstration - in a real system,
     * this would delegate to an application-specific execution engine.
     */
    public byte[] execute() {
        // Simple demonstration: echo the request with "EXECUTED:" prefix
        String requestStr = new String(requestData);
        String result = "EXECUTED: " + requestStr;
        return result.getBytes();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ExecutableRequest)) return false;
        ExecutableRequest other = (ExecutableRequest) obj;
        return Arrays.equals(requestData, other.requestData) && 
               requestId.equals(other.requestId);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(requestData) * 31 + requestId.hashCode();
    }
    
    @Override
    public String toString() {
        return "ExecutableRequest{" +
                "requestId='" + requestId + '\'' +
                ", requestData=" + new String(requestData) +
                '}';
    }
    
    /**
     * Creates an ExecutableRequest from raw bytes with auto-generated ID.
     */
    public static ExecutableRequest fromBytes(byte[] data) {
        String autoId = "req-" + System.currentTimeMillis() + "-" + Arrays.hashCode(data);
        return new ExecutableRequest(data, autoId);
    }
} 