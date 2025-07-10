package replicated.algorithms.paxos;

/**
 * Phase 1a message: Prepare request with proposal number.
 */
public final class PrepareRequest {
    private ProposalNumber proposalNumber;
    private String correlationId;
    
    // Default constructor for JSON deserialization
    public PrepareRequest() {
    }
    
    public PrepareRequest(ProposalNumber proposalNumber, String correlationId) {
        if (proposalNumber == null) {
            throw new IllegalArgumentException("Proposal number cannot be null");
        }
        if (correlationId == null || correlationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Correlation ID cannot be null or empty");
        }
        this.proposalNumber = proposalNumber;
        this.correlationId = correlationId;
    }
    
    public ProposalNumber getProposalNumber() {
        return proposalNumber;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PrepareRequest)) return false;
        PrepareRequest other = (PrepareRequest) obj;
        return proposalNumber.equals(other.proposalNumber) && correlationId.equals(other.correlationId);
    }
    
    @Override
    public int hashCode() {
        return proposalNumber.hashCode() * 31 + correlationId.hashCode();
    }
    
    @Override
    public String toString() {
        return "PrepareRequest{" +
                "proposalNumber=" + proposalNumber +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
} 