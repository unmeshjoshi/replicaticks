package replicated.algorithms.paxos;

import java.util.Arrays;

/**
 * Phase 1b message: Promise response from acceptor to proposer.
 */
public final class PromiseResponse {
    private ProposalNumber proposalNumber;
    private boolean promised;
    private ProposalNumber acceptedProposal;
    private byte[] acceptedValue;
    private String correlationId;
    
    // Default constructor for JSON deserialization
    public PromiseResponse() {
    }
    
    public PromiseResponse(ProposalNumber proposalNumber, boolean promised, 
                          ProposalNumber acceptedProposal, byte[] acceptedValue, String correlationId) {
        if (proposalNumber == null) {
            throw new IllegalArgumentException("Proposal number cannot be null");
        }
        if (correlationId == null || correlationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Correlation ID cannot be null or empty");
        }
        this.proposalNumber = proposalNumber;
        this.promised = promised;
        this.acceptedProposal = acceptedProposal;
        this.acceptedValue = acceptedValue != null ? acceptedValue.clone() : null;
        this.correlationId = correlationId;
    }
    
    /**
     * Creates a promise response with no previously accepted value.
     */
    public static PromiseResponse promise(ProposalNumber proposalNumber, String correlationId) {
        return new PromiseResponse(proposalNumber, true, null, null, correlationId);
    }
    
    /**
     * Creates a promise response with previously accepted proposal and value.
     */
    public static PromiseResponse promiseWithAccepted(ProposalNumber proposalNumber, 
                                                     ProposalNumber acceptedProposal, 
                                                     byte[] acceptedValue, 
                                                     String correlationId) {
        return new PromiseResponse(proposalNumber, true, acceptedProposal, acceptedValue, correlationId);
    }
    
    /**
     * Creates a rejection response (promise refused).
     */
    public static PromiseResponse reject(ProposalNumber proposalNumber, String correlationId) {
        return new PromiseResponse(proposalNumber, false, null, null, correlationId);
    }
    
    public ProposalNumber getProposalNumber() {
        return proposalNumber;
    }
    
    public ProposalNumber getAcceptedProposal() {
        return acceptedProposal;
    }
    
    public byte[] getAcceptedValue() {
        return acceptedValue != null ? acceptedValue.clone() : null;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public boolean isPromised() {
        return promised;
    }
    
    public boolean hasAcceptedValue() {
        return acceptedProposal != null && acceptedValue != null;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PromiseResponse)) return false;
        PromiseResponse other = (PromiseResponse) obj;
        return promised == other.promised &&
               proposalNumber.equals(other.proposalNumber) &&
               java.util.Objects.equals(acceptedProposal, other.acceptedProposal) &&
               Arrays.equals(acceptedValue, other.acceptedValue) &&
               correlationId.equals(other.correlationId);
    }
    
    @Override
    public int hashCode() {
        int result = proposalNumber.hashCode();
        result = 31 * result + Boolean.hashCode(promised);
        result = 31 * result + (acceptedProposal != null ? acceptedProposal.hashCode() : 0);
        result = 31 * result + Arrays.hashCode(acceptedValue);
        result = 31 * result + correlationId.hashCode();
        return result;
    }
    
    @Override
    public String toString() {
        return "PromiseResponse{" +
                "proposalNumber=" + proposalNumber +
                ", acceptedProposal=" + acceptedProposal +
                ", hasAcceptedValue=" + (acceptedValue != null) +
                ", correlationId='" + correlationId + '\'' +
                ", promised=" + promised +
                '}';
    }
} 