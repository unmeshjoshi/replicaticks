package replicated.algorithms.paxos;

import replicated.messaging.AsyncQuorumCallback;
import replicated.network.MessageContext;

/**
 * Context for tracking Phase 2 Paxos proposals.
 * Contains all information needed to manage ACCEPT/ACCEPTED phase.
 */
public class Phase2ProposalContext {
    public final ProposalNumber proposalNumber;
    public final byte[] value;
    public final String correlationId;
    public final MessageContext originalContext;
    public final AsyncQuorumCallback<AcceptedResponse> quorumCallback;
    
    public Phase2ProposalContext(ProposalNumber proposalNumber, byte[] value, String correlationId, 
                               MessageContext originalContext, AsyncQuorumCallback<AcceptedResponse> quorumCallback) {
        this.proposalNumber = proposalNumber;
        this.value = value != null ? value.clone() : null;
        this.correlationId = correlationId;
        this.originalContext = originalContext;
        this.quorumCallback = quorumCallback;
    }
} 