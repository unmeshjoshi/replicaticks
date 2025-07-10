package replicated.algorithms.paxos;

import java.util.Arrays;

/**
 * Commit phase message: Instructs acceptors to commit the agreed value.
 */
public final class CommitRequest {
    private ProposalNumber proposalNumber;
    private byte[] value;
    private String correlationId;
    
    // Default constructor for JSON deserialization
    public CommitRequest() {
    }
    
    public CommitRequest(ProposalNumber proposalNumber, byte[] value, String correlationId) {
        if (proposalNumber == null) {
            throw new IllegalArgumentException("Proposal number cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (correlationId == null || correlationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Correlation ID cannot be null or empty");
        }
        this.proposalNumber = proposalNumber;
        this.value = value.clone(); // Defensive copy
        this.correlationId = correlationId;
    }
    
    public ProposalNumber getProposalNumber() {
        return proposalNumber;
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
        if (!(obj instanceof CommitRequest)) return false;
        CommitRequest other = (CommitRequest) obj;
        return proposalNumber.equals(other.proposalNumber) &&
               Arrays.equals(value, other.value) &&
               correlationId.equals(other.correlationId);
    }
    
    @Override
    public int hashCode() {
        int result = proposalNumber.hashCode();
        result = 31 * result + Arrays.hashCode(value);
        result = 31 * result + correlationId.hashCode();
        return result;
    }
    
    @Override
    public String toString() {
        return "CommitRequest{" +
                "proposalNumber=" + proposalNumber +
                ", value=" + new String(value) +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
} 