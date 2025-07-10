package replicated.algorithms.paxos;

import java.util.Arrays;

/**
 * Response to client propose request indicating success/failure and execution result.
 */
public final class ProposeResponse {
    private boolean success;
    private byte[] executionResult;
    private String correlationId;
    private String errorMessage;
    
    // Default constructor for JSON deserialization
    public ProposeResponse() {
    }
    
    public ProposeResponse(boolean success, byte[] executionResult, String correlationId, String errorMessage) {
        if (correlationId == null || correlationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Correlation ID cannot be null or empty");
        }
        this.success = success;
        this.executionResult = executionResult != null ? executionResult.clone() : null;
        this.correlationId = correlationId;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Creates a successful response with execution result.
     */
    public static ProposeResponse success(byte[] executionResult, String correlationId) {
        return new ProposeResponse(true, executionResult, correlationId, null);
    }
    
    /**
     * Creates a failure response with error message.
     */
    public static ProposeResponse failure(String errorMessage, String correlationId) {
        return new ProposeResponse(false, null, correlationId, errorMessage);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public byte[] getExecutionResult() {
        return executionResult != null ? executionResult.clone() : null;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProposeResponse)) return false;
        ProposeResponse other = (ProposeResponse) obj;
        return success == other.success &&
               Arrays.equals(executionResult, other.executionResult) &&
               correlationId.equals(other.correlationId) &&
               java.util.Objects.equals(errorMessage, other.errorMessage);
    }
    
    @Override
    public int hashCode() {
        int result = Boolean.hashCode(success);
        result = 31 * result + Arrays.hashCode(executionResult);
        result = 31 * result + correlationId.hashCode();
        result = 31 * result + (errorMessage != null ? errorMessage.hashCode() : 0);
        return result;
    }
    
    @Override
    public String toString() {
        return "ProposeResponse{" +
                "success=" + success +
                ", hasExecutionResult=" + (executionResult != null) +
                ", correlationId='" + correlationId + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
} 