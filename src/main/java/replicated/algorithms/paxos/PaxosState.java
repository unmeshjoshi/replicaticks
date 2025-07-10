package replicated.algorithms.paxos;

/**
 * Represents the persistent state of a Paxos replica.
 * Tracks the highest promised proposal and the accepted proposal/value.
 */
public final class PaxosState {
    private ProposalNumber highestPromised;
    private ProposalNumber acceptedProposal;
    private byte[] acceptedValue;
    private boolean hasCommittedValue;
    private byte[] committedValue;
    
    public PaxosState() {
        this.highestPromised = null;
        this.acceptedProposal = null;
        this.acceptedValue = null;
        this.hasCommittedValue = false;
        this.committedValue = null;
    }
    
    /**
     * Returns the highest proposal number this replica has promised.
     */
    public ProposalNumber getHighestPromised() {
        return highestPromised;
    }
    
    /**
     * Returns the accepted proposal number.
     */
    public ProposalNumber getAcceptedProposal() {
        return acceptedProposal;
    }
    
    /**
     * Returns the accepted value.
     */
    public byte[] getAcceptedValue() {
        return acceptedValue;
    }
    
    /**
     * Returns true if this replica has a committed value.
     */
    public boolean hasCommittedValue() {
        return hasCommittedValue;
    }
    
    /**
     * Returns the committed value.
     */
    public byte[] getCommittedValue() {
        return committedValue;
    }
    
    /**
     * Updates the highest promised proposal number.
     * Should only be called if the new proposal is higher than current.
     */
    public void updateHighestPromised(ProposalNumber proposalNumber) {
        if (proposalNumber == null) {
            throw new IllegalArgumentException("Proposal number cannot be null");
        }
        if (highestPromised != null && !proposalNumber.isHigherThan(highestPromised)) {
            throw new IllegalArgumentException("New proposal must be higher than current highest promised");
        }
        this.highestPromised = proposalNumber;
    }
    
    /**
     * Updates the accepted proposal and value.
     * Should only be called if the proposal is at least as high as promised.
     */
    public void updateAccepted(ProposalNumber proposalNumber, byte[] value) {
        if (proposalNumber == null) {
            throw new IllegalArgumentException("Proposal number cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (highestPromised != null && proposalNumber.compareTo(highestPromised) < 0) {
            throw new IllegalArgumentException("Cannot accept proposal lower than promised");
        }
        
        this.acceptedProposal = proposalNumber;
        this.acceptedValue = value.clone(); // Defensive copy
        
        // Update highest promised if this proposal is higher
        if (highestPromised == null || proposalNumber.isHigherThan(highestPromised)) {
            this.highestPromised = proposalNumber;
        }
    }
    
    /**
     * Commits a value as the final consensus result.
     */
    public void commitValue(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Committed value cannot be null");
        }
        this.hasCommittedValue = true;
        this.committedValue = value.clone(); // Defensive copy
    }
    
    /**
     * Returns true if the given proposal number can be promised.
     * A proposal can be promised if it's higher than any previously promised proposal.
     */
    public boolean canPromise(ProposalNumber proposalNumber) {
        if (proposalNumber == null) {
            return false;
        }
        return highestPromised == null || proposalNumber.isHigherThan(highestPromised);
    }
    
    /**
     * Returns true if the given proposal number can be accepted.
     * A proposal can be accepted if it's at least as high as the promised proposal.
     */
    public boolean canAccept(ProposalNumber proposalNumber) {
        if (proposalNumber == null) {
            return false;
        }
        return highestPromised == null || proposalNumber.compareTo(highestPromised) >= 0;
    }
    
    @Override
    public String toString() {
        return "PaxosState{" +
                "highestPromised=" + highestPromised +
                ", acceptedProposal=" + acceptedProposal +
                ", hasAcceptedValue=" + (acceptedValue != null) +
                ", hasCommittedValue=" + hasCommittedValue +
                '}';
    }
} 