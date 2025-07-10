package replicated.algorithms.paxos;

/**
 * Data transfer object for PaxosState serialization.
 * This class uses public fields to work well with JSON serialization.
 */
public class PaxosStateData {
    public ProposalNumber highestPromised;
    public ProposalNumber acceptedProposal;
    public byte[] acceptedValue;
    public boolean hasCommittedValue;
    public byte[] committedValue;
    
    // Default constructor for JSON deserialization
    public PaxosStateData() {
    }
    
    public PaxosStateData(ProposalNumber highestPromised, ProposalNumber acceptedProposal, 
                         byte[] acceptedValue, boolean hasCommittedValue, byte[] committedValue) {
        this.highestPromised = highestPromised;
        this.acceptedProposal = acceptedProposal;
        this.acceptedValue = acceptedValue != null ? acceptedValue.clone() : null;
        this.hasCommittedValue = hasCommittedValue;
        this.committedValue = committedValue != null ? committedValue.clone() : null;
    }
} 