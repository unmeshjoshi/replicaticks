package replicated.algorithms.paxos;

import replicated.messaging.AsyncQuorumCallback;
import replicated.network.MessageContext;

/**
 * Context for tracking active Paxos proposals.
 * Contains all information needed to manage a proposal through multiple phases.
 */
public class ProposalContext {
    public final ProposalNumber proposalNumber;
    public final byte[] value;
    public final String correlationId;
    public final MessageContext originalContext;
    public final AsyncQuorumCallback<PromiseResponse> quorumCallback;
    
    public ProposalContext(ProposalNumber proposalNumber, byte[] value, String correlationId, 
                          MessageContext originalContext, AsyncQuorumCallback<PromiseResponse> quorumCallback) {
        this.proposalNumber = proposalNumber;
        this.value = value != null ? value.clone() : null;
        this.correlationId = correlationId;
        this.originalContext = originalContext;
        this.quorumCallback = quorumCallback;
    }
} 