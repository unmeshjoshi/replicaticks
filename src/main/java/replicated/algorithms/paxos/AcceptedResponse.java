package replicated.algorithms.paxos;

/**
 * Phase 2b message: Accepted response from acceptor to proposer.
 */
public final class AcceptedResponse {
    private ProposalNumber proposalNumber;
    private boolean accepted;
    private String correlationId;
    
    // Default constructor for JSON deserialization
    public AcceptedResponse() {
    }
    
    public AcceptedResponse(ProposalNumber proposalNumber, boolean accepted, String correlationId) {
        if (proposalNumber == null) {
            throw new IllegalArgumentException("Proposal number cannot be null");
        }
        if (correlationId == null || correlationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Correlation ID cannot be null or empty");
        }
        this.proposalNumber = proposalNumber;
        this.correlationId = correlationId;
        this.accepted = accepted;
    }
    
    /**
     * Creates an accepted response indicating the proposal was accepted.
     */
    public static AcceptedResponse accept(ProposalNumber proposalNumber, String correlationId) {
        return new AcceptedResponse(proposalNumber, true, correlationId);
    }

    /**
     * Creates a rejected response indicating the proposal was not accepted.
     */
    public static AcceptedResponse reject(ProposalNumber proposalNumber, String correlationId) {
        return new AcceptedResponse(proposalNumber, false, correlationId);
    }
    
    public ProposalNumber getProposalNumber() {
        return proposalNumber;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public boolean isAccepted() {
        return accepted;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AcceptedResponse)) return false;
        AcceptedResponse other = (AcceptedResponse) obj;
        return accepted == other.accepted &&
               proposalNumber.equals(other.proposalNumber) &&
               correlationId.equals(other.correlationId);
    }
    
    @Override
    public int hashCode() {
        int result = proposalNumber.hashCode();
        result = 31 * result + correlationId.hashCode();
        result = 31 * result + Boolean.hashCode(accepted);
        return result;
    }
    
    @Override
    public String toString() {
        return "AcceptedResponse{" +
                "proposalNumber=" + proposalNumber +
                ", correlationId='" + correlationId + '\'' +
                ", accepted=" + accepted +
                '}';
    }
} 