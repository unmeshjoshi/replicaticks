package replicated.algorithms.paxos;

import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.replica.Replica;
import replicated.storage.Storage;
import replicated.storage.VersionedValue;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * Paxos consensus algorithm implementation.
 * 
 * This replica simultaneously acts as Proposer, Acceptor, and Learner,
 * coordinating through Messages sent via the MessageBus to agree on and
 * execute arbitrary requests.
 */
public final class PaxosReplica extends Replica {
    
    // State management
    private final PaxosState paxosState;
    private final AtomicLong generationCounter;
    private final ConcurrentHashMap<String, ProposalNumber> activeProposals;
    private final ConcurrentHashMap<String, ProposalContext> activeProposalContexts;
    private final ConcurrentHashMap<String, Phase2ProposalContext> activePhase2Contexts;
    
    // Storage keys for persistence
    private static final String PAXOS_STATE_KEY = "paxos_state";
    private static final String GENERATION_COUNTER_KEY = "generation_counter";
    
    /**
     * Creates a PaxosReplica with the specified configuration.
     */
    public PaxosReplica(String name, NetworkAddress address, List<NetworkAddress> peers, 
                       MessageBus messageBus, MessageCodec messageCodec, Storage storage) {
        super(name, address, peers, messageBus, messageCodec, storage, 5000);
        
        this.paxosState = new PaxosState();
        this.generationCounter = new AtomicLong(0);
        this.activeProposals = new ConcurrentHashMap<>();
        this.activeProposalContexts = new ConcurrentHashMap<>();
        this.activePhase2Contexts = new ConcurrentHashMap<>();
        
        // Load persistent state
        loadState();
    }
    
    @Override
    public void onMessageReceived(Message message, MessageContext context) {
        try {
            switch (message.messageType()) {
                case PAXOS_PROPOSE_REQUEST:
                    handleProposeRequest(message, context);
                    break;
                case PAXOS_PREPARE_REQUEST:
                    handlePrepareRequest(message, context);
                    break;
                case PAXOS_PROMISE_RESPONSE:
                    handlePromiseResponse(message, context);
                    break;
                case PAXOS_ACCEPT_REQUEST:
                    handleAcceptRequest(message, context);
                    break;
                case PAXOS_ACCEPTED_RESPONSE:
                    handleAcceptedResponse(message, context);
                    break;
                case PAXOS_COMMIT_REQUEST:
                    handleCommitRequest(message, context);
                    break;
                default:
                    // Log unhandled message types
                    System.out.println("PaxosReplica: Unhandled message type: " + message.messageType());
            }
        } catch (Exception e) {
            // Log error and send failure response if applicable
            System.err.println("Error handling message " + message.messageType() + ": " + e.getMessage());
            e.printStackTrace();
            
            // Send failure response for client requests
            if (message.messageType() == MessageType.PAXOS_PROPOSE_REQUEST) {
                sendFailureResponse(message, context, "Internal error: " + e.getMessage());
            }
        }
    }
    
    // === CLIENT REQUEST HANDLING ===
    
    /**
     * Handle client request to propose a value.
     */
    private void handleProposeRequest(Message message, MessageContext context) {
        ProposeRequest request = deserializePayload(message.payload(), ProposeRequest.class);
        
        // Start Paxos consensus for this value
        startPaxosProposal(request.getValue(), request.getCorrelationId(), context);
    }
    
    // === PAXOS PHASE 1: PREPARE/PROMISE ===
    
    /**
     * Handle PREPARE request (Phase 1a) - Acceptor role.
     */
    private void handlePrepareRequest(Message message, MessageContext context) {
        PrepareRequest request = deserializePayload(message.payload(), PrepareRequest.class);
        
        // Check if we can promise this proposal
        if (paxosState.canPromise(request.getProposalNumber())) {
            // Update our promised proposal
            paxosState.updateHighestPromised(request.getProposalNumber());
            persistState();
            
            // Send promise with any previously accepted value
            PromiseResponse response;
            if (paxosState.getAcceptedProposal() != null) {
                response = PromiseResponse.promiseWithAccepted(
                    request.getProposalNumber(),
                    paxosState.getAcceptedProposal(),
                    paxosState.getAcceptedValue(),
                    message.correlationId()  // Use the message's correlation ID, not request's
                );
            } else {
                response = PromiseResponse.promise(
                    request.getProposalNumber(),
                    message.correlationId()  // Use the message's correlation ID, not request's
                );
            }
            
            Message responseMessage = new Message(
                getNetworkAddress(), message.source(), MessageType.PAXOS_PROMISE_RESPONSE,
                serializePayload(response), message.correlationId()  // Use the message's correlation ID
            );
            messageBus.sendMessage(responseMessage);
        } else {
            // Reject the prepare request
            PromiseResponse response = PromiseResponse.reject(
                request.getProposalNumber(),
                message.correlationId()  // Use the message's correlation ID, not request's
            );
            Message rejectMessage = new Message(
                getNetworkAddress(), message.source(), MessageType.PAXOS_PROMISE_RESPONSE,
                serializePayload(response), message.correlationId()  // Use the message's correlation ID
            );
            messageBus.sendMessage(rejectMessage);
        }
    }
    
    /**
     * Handle PROMISE response (Phase 1b) - Proposer role.
     */
    private void handlePromiseResponse(Message message, MessageContext context) {
        PromiseResponse response = deserializePayload(message.payload(), PromiseResponse.class);
        
        // Find the corresponding proposal context
        String correlationId = response.getCorrelationId();
        ProposalContext proposalContext = activeProposalContexts.get(correlationId);
        
        if (proposalContext != null) {
            if (response.isPromised()) {
                System.out.println("Received promise from " + message.source() + " for proposal " + response.getProposalNumber());
            } else {
                System.out.println("Received promise rejection from " + message.source() + " for proposal " + response.getProposalNumber());
            }
            
            // Feed response to the quorum callback
            proposalContext.quorumCallback.onResponse(response, message.source());
        } else {
            System.out.println("Received promise response for unknown proposal: " + response);
        }
    }
    
    // === PAXOS PHASE 2: ACCEPT/ACCEPTED ===
    
    /**
     * Handle ACCEPT request (Phase 2a) - Acceptor role.
     */
    private void handleAcceptRequest(Message message, MessageContext context) {
        AcceptRequest request = deserializePayload(message.payload(), AcceptRequest.class);
        
        // Check if we can accept this proposal
        if (paxosState.canAccept(request.getProposalNumber())) {
            // Accept the proposal
            paxosState.updateAccepted(request.getProposalNumber(), request.getValue());
            persistState();
            
            // Send accepted response
            AcceptedResponse response = AcceptedResponse.accept(
                request.getProposalNumber(),
                message.correlationId()  // Use the message's correlation ID, not request's
            );
            Message acceptMessage = new Message(
                getNetworkAddress(), message.source(), MessageType.PAXOS_ACCEPTED_RESPONSE,
                serializePayload(response), message.correlationId()  // Use the message's correlation ID
            );
            messageBus.sendMessage(acceptMessage);
        } else {
            // Reject the accept request
            AcceptedResponse response = AcceptedResponse.reject(
                request.getProposalNumber(),
                message.correlationId()  // Use the message's correlation ID, not request's
            );
            Message rejectMessage = new Message(
                getNetworkAddress(), message.source(), MessageType.PAXOS_ACCEPTED_RESPONSE,
                serializePayload(response), message.correlationId()  // Use the message's correlation ID
            );
            messageBus.sendMessage(rejectMessage);
        }
    }
    
    /**
     * Handle ACCEPTED response (Phase 2b) - Proposer role.
     */
    private void handleAcceptedResponse(Message message, MessageContext context) {
        AcceptedResponse response = deserializePayload(message.payload(), AcceptedResponse.class);
        
        // Find the corresponding Phase 2 proposal context
        String correlationId = response.getCorrelationId();
        Phase2ProposalContext phase2Context = activePhase2Contexts.get(correlationId);
        
        if (phase2Context != null) {
            if (response.isAccepted()) {
                System.out.println("Received accept from " + message.source() + " for proposal " + response.getProposalNumber());
            } else {
                System.out.println("Received accept rejection from " + message.source() + " for proposal " + response.getProposalNumber());
            }
            
            // Feed response to the quorum callback
            phase2Context.quorumCallback.onResponse(response, message.source());
        } else {
            System.out.println("Received accepted response for unknown proposal: " + response);
        }
    }
    
    // === PAXOS COMMIT PHASE ===
    
    /**
     * Handle COMMIT request - Learner role.
     */
    private void handleCommitRequest(Message message, MessageContext context) {
        CommitRequest request = deserializePayload(message.payload(), CommitRequest.class);
        
        // Commit the value and execute the request
        paxosState.commitValue(request.getValue());
        persistState();
        
        // Execute the committed request
        ExecutableRequest executableRequest = ExecutableRequest.fromBytes(request.getValue());
        byte[] result = executableRequest.execute();
        
        System.out.println("Committed and executed: " + executableRequest + " -> " + new String(result));
        
        // If this was our proposal, respond to the client
        ProposalNumber ourProposal = activeProposals.get(request.getCorrelationId());
        if (ourProposal != null && ourProposal.equals(request.getProposalNumber())) {
            // Send success response to client
            ProposeResponse response = ProposeResponse.success(result, request.getCorrelationId());
            // TODO: Send to original client (need to track client address)
            System.out.println("Would send success response: " + response);
        }
    }
    
    // === HELPER METHODS ===
    
    /**
     * Start a new Paxos proposal for the given value.
     */
    private void startPaxosProposal(byte[] value, String correlationId, MessageContext context) {
        // Generate new proposal number
        long generation = generationCounter.incrementAndGet();
        ProposalNumber proposalNumber = new ProposalNumber(generation, getNetworkAddress());
        
        // Track this proposal
        activeProposals.put(correlationId, proposalNumber);
        
        // Persist the updated generation counter
        persistState();
        
        // Create quorum callback for Phase 1 (PREPARE/PROMISE)
        AsyncQuorumCallback<PromiseResponse> quorumCallback = createPrepareQuorumCallback(proposalNumber, value, correlationId, context);
        
        // Store proposal context for response handling  
        ProposalContext proposalContext = new ProposalContext(proposalNumber, value, correlationId, context, quorumCallback);
        activeProposalContexts.put(correlationId, proposalContext);
        
        // Start Phase 1: Send PREPARE to all replicas manually
        System.out.println("Started Paxos proposal " + proposalNumber + " for value: " + new String(value));
        System.out.println("Sending PREPARE to nodes: " + getAllNodes());
        for (NetworkAddress node : getAllNodes()) {
            System.out.println("Sending PREPARE to: " + node);
            PrepareRequest prepareRequest = new PrepareRequest(proposalNumber, correlationId);
            Message prepareMessage = new Message(
                getNetworkAddress(), node, MessageType.PAXOS_PREPARE_REQUEST,
                serializePayload(prepareRequest), correlationId  // Use client correlation ID for simple routing
            );
            messageBus.sendMessage(prepareMessage);
        }
    }
    
    /**
     * Create quorum callback for Phase 1 (PREPARE/PROMISE).
     */
    private AsyncQuorumCallback<PromiseResponse> createPrepareQuorumCallback(ProposalNumber proposalNumber, 
                                                                           byte[] value, String correlationId, 
                                                                           MessageContext originalContext) {
        List<NetworkAddress> allNodes = getAllNodes();
        int majoritySize = (allNodes.size() / 2) + 1;
        
        return new AsyncQuorumCallback<PromiseResponse>(
            allNodes.size(),
            response -> response != null && response.isPromised()
        ).onSuccess(promises -> {
            // Phase 1 succeeded - we have a majority of promises
            System.out.println("Phase 1 completed with " + promises.size() + " promises for proposal " + proposalNumber);
            
            // Determine the value to propose in Phase 2
            byte[] proposalValue = determinePhase2Value(promises, value);
            
            // Start Phase 2 (ACCEPT)
            startPhase2Accept(proposalNumber, proposalValue, correlationId, originalContext);
            
        }).onFailure(error -> {
            // Phase 1 failed - clean up and respond to client
            System.out.println("Phase 1 failed for proposal " + proposalNumber + ": " + error.getMessage());
            activeProposals.remove(correlationId);
            activeProposalContexts.remove(correlationId);
            
            // Send failure response to client
            sendClientFailureResponse(correlationId, originalContext, "Failed to achieve majority in Phase 1: " + error.getMessage());
        });
    }
    
    /**
     * Determine the value to propose in Phase 2 based on promises received.
     * If any acceptor has previously accepted a value, use the one with the highest proposal number.
     * Otherwise, use our original value.
     */
    private byte[] determinePhase2Value(Map<NetworkAddress, PromiseResponse> promises, byte[] originalValue) {
        ProposalNumber highestAcceptedProposal = null;
        byte[] highestAcceptedValue = null;
        
        for (PromiseResponse promise : promises.values()) {
            if (promise.hasAcceptedValue()) {
                ProposalNumber acceptedProposal = promise.getAcceptedProposal();
                if (highestAcceptedProposal == null || acceptedProposal.isHigherThan(highestAcceptedProposal)) {
                    highestAcceptedProposal = acceptedProposal;
                    highestAcceptedValue = promise.getAcceptedValue();
                }
            }
        }
        
        // Use previously accepted value if found, otherwise use our original value
        return highestAcceptedValue != null ? highestAcceptedValue : originalValue;
    }
    
    /**
     * Start Phase 2 (ACCEPT) with the determined value.
     */
    private void startPhase2Accept(ProposalNumber proposalNumber, byte[] value, String correlationId, MessageContext originalContext) {
        System.out.println("Starting Phase 2 ACCEPT for proposal " + proposalNumber + " with value: " + new String(value));
        
        // Create quorum callback for Phase 2 (ACCEPT/ACCEPTED)
        AsyncQuorumCallback<AcceptedResponse> acceptQuorumCallback = createAcceptQuorumCallback(proposalNumber, value, correlationId, originalContext);
        
        // Update proposal context with the new quorum callback for Phase 2
        ProposalContext proposalContext = activeProposalContexts.get(correlationId);
        if (proposalContext != null) {
            // Create new context for Phase 2 - we need a different type for AcceptedResponse
            Phase2ProposalContext phase2Context = new Phase2ProposalContext(proposalNumber, value, correlationId, originalContext, acceptQuorumCallback);
            activePhase2Contexts.put(correlationId, phase2Context);
        }
        
        // Start Phase 2: Send ACCEPT to all replicas manually
        for (NetworkAddress node : getAllNodes()) {
            AcceptRequest acceptRequest = new AcceptRequest(proposalNumber, value, correlationId);
            Message acceptMessage = new Message(
                getNetworkAddress(), node, MessageType.PAXOS_ACCEPT_REQUEST,
                serializePayload(acceptRequest), correlationId  // Use client correlation ID for simple routing
            );
            messageBus.sendMessage(acceptMessage);
        }
        
        System.out.println("Phase 2 ACCEPT broadcast completed for proposal " + proposalNumber);
    }
    
    /**
     * Create quorum callback for Phase 2 (ACCEPT/ACCEPTED).
     */
    private AsyncQuorumCallback<AcceptedResponse> createAcceptQuorumCallback(ProposalNumber proposalNumber, 
                                                                           byte[] value, String correlationId, 
                                                                           MessageContext originalContext) {
        List<NetworkAddress> allNodes = getAllNodes();
        
        return new AsyncQuorumCallback<AcceptedResponse>(
            allNodes.size(),
            response -> response != null && response.isAccepted()
        ).onSuccess(acceptedResponses -> {
            // Phase 2 succeeded - we have a majority of accepts
            System.out.println("Phase 2 completed with " + acceptedResponses.size() + " accepts for proposal " + proposalNumber);
            
            // Start commit phase - broadcast COMMIT to all replicas
            startCommitPhase(proposalNumber, value, correlationId, originalContext);
            
        }).onFailure(error -> {
            // Phase 2 failed - clean up and respond to client
            System.out.println("Phase 2 failed for proposal " + proposalNumber + ": " + error.getMessage());
            activeProposals.remove(correlationId);
            activeProposalContexts.remove(correlationId);
            activePhase2Contexts.remove(correlationId);
            
            // Send failure response to client
            sendClientFailureResponse(correlationId, originalContext, "Failed to achieve majority in Phase 2: " + error.getMessage());
        });
    }
    
    /**
     * Start commit phase - broadcast COMMIT messages to all replicas.
     */
    private void startCommitPhase(ProposalNumber proposalNumber, byte[] value, String correlationId, MessageContext originalContext) {
        System.out.println("Starting commit phase for proposal " + proposalNumber + " with value: " + new String(value));
        
        // Create COMMIT request
        CommitRequest commitRequest = new CommitRequest(proposalNumber, value, correlationId);
        
        // Broadcast COMMIT to all replicas (including self)
        for (NetworkAddress peer : getAllNodes()) {
            Message commitMessage = new Message(
                getNetworkAddress(), peer, MessageType.PAXOS_COMMIT_REQUEST,
                serializePayload(commitRequest), correlationId
            );
            messageBus.sendMessage(commitMessage);
        }
        
        // Clean up proposal contexts
        activeProposals.remove(correlationId);
        activeProposalContexts.remove(correlationId);
        activePhase2Contexts.remove(correlationId);
        
        // Send success response to client
        sendClientSuccessResponse(correlationId, originalContext, value);
        
        System.out.println("Commit phase completed for proposal " + proposalNumber);
    }
    
    /**
     * Send failure response to client.
     */
    private void sendFailureResponse(Message originalMessage, MessageContext context, String errorMessage) {
        try {
            ProposeRequest request = deserializePayload(originalMessage.payload(), ProposeRequest.class);
            ProposeResponse response = ProposeResponse.failure(errorMessage, request.getCorrelationId());
            Message responseMessage = new Message(
                getNetworkAddress(), originalMessage.source(), MessageType.PAXOS_PROPOSE_RESPONSE,
                serializePayload(response), request.getCorrelationId()
            );
            messageBus.sendMessage(responseMessage);
        } catch (Exception e) {
            System.err.println("Failed to send failure response: " + e.getMessage());
        }
    }
    
    /**
     * Send failure response to the original client.
     */
    private void sendClientFailureResponse(String correlationId, MessageContext originalContext, String errorMessage) {
        try {
            ProposeResponse response = ProposeResponse.failure(errorMessage, correlationId);
            Message responseMessage = new Message(
                getNetworkAddress(), originalContext.getMessage().source(), MessageType.PAXOS_PROPOSE_RESPONSE,
                serializePayload(response), correlationId
            );
            messageBus.reply(originalContext, responseMessage);
        } catch (Exception e) {
            System.err.println("Failed to send client failure response: " + e.getMessage());
        }
    }
    
    /**
     * Send success response to the original client.
     */
    private void sendClientSuccessResponse(String correlationId, MessageContext originalContext, byte[] result) {
        try {
            ProposeResponse response = ProposeResponse.success(result, correlationId);
            Message responseMessage = new Message(
                getNetworkAddress(), originalContext.getMessage().source(), MessageType.PAXOS_PROPOSE_RESPONSE,
                serializePayload(response), correlationId
            );
            messageBus.reply(originalContext, responseMessage);
            System.out.println("Sent success response for correlation " + correlationId);
        } catch (Exception e) {
            System.err.println("Failed to send client success response: " + e.getMessage());
        }
    }
    
    /**
     * Load persistent state from storage.
     */
    private void loadState() {
        try {
            // Load generation counter asynchronously
            storage.get(GENERATION_COUNTER_KEY.getBytes()).onSuccess(versionedValue -> {
                if (versionedValue != null) {
                    long savedGeneration = Long.parseLong(new String(versionedValue.value()));
                    generationCounter.set(savedGeneration);
                    System.out.println("Loaded generation counter: " + generationCounter.get());
                } else {
                    System.out.println("No previous generation counter found, starting with 0");
                }
            }).onFailure(error -> {
                System.err.println("Failed to load generation counter: " + error.getMessage());
            });
            
            // Load PaxosState from storage
            storage.get(PAXOS_STATE_KEY.getBytes()).onSuccess(versionedValue -> {
                if (versionedValue != null) {
                    try {
                        String jsonData = new String(versionedValue.value());
                        PaxosStateData stateData = deserializePayload(jsonData.getBytes(), PaxosStateData.class);
                        
                        // Restore the PaxosState from the loaded data
                        if (stateData.highestPromised != null) {
                            paxosState.updateHighestPromised(stateData.highestPromised);
                        }
                        if (stateData.acceptedProposal != null && stateData.acceptedValue != null) {
                            paxosState.updateAccepted(stateData.acceptedProposal, stateData.acceptedValue);
                        }
                        if (stateData.hasCommittedValue && stateData.committedValue != null) {
                            paxosState.commitValue(stateData.committedValue);
                        }
                        
                        System.out.println("Loaded PaxosState: " + paxosState);
                    } catch (Exception e) {
                        System.err.println("Failed to deserialize PaxosState: " + e.getMessage());
                    }
                } else {
                    System.out.println("No previous PaxosState found, starting with fresh state");
                }
            }).onFailure(error -> {
                System.err.println("Failed to load PaxosState: " + error.getMessage());
            });
        } catch (Exception e) {
            System.err.println("Failed to load state: " + e.getMessage());
        }
    }
    
    /**
     * Persist current state to storage.
     */
    private void persistState() {
        try {
            // Save generation counter asynchronously
            byte[] generationData = String.valueOf(generationCounter.get()).getBytes();
            VersionedValue generationVersionedValue = new VersionedValue(generationData, System.currentTimeMillis());
            
            storage.set(GENERATION_COUNTER_KEY.getBytes(), generationVersionedValue).onSuccess(success -> {
                if (success) {
                    System.out.println("Persisted generation counter: " + generationCounter.get());
                } else {
                    System.err.println("Failed to persist generation counter: " + generationCounter.get());
                }
            }).onFailure(error -> {
                System.err.println("Failed to persist generation counter: " + error.getMessage());
            });
            
            // Save PaxosState to storage
            try {
                PaxosStateData stateData = new PaxosStateData(
                    paxosState.getHighestPromised(),
                    paxosState.getAcceptedProposal(),
                    paxosState.getAcceptedValue(),
                    paxosState.hasCommittedValue(),
                    paxosState.getCommittedValue()
                );
                
                byte[] stateJsonData = serializePayload(stateData);
                VersionedValue stateVersionedValue = new VersionedValue(stateJsonData, System.currentTimeMillis());
                
                storage.set(PAXOS_STATE_KEY.getBytes(), stateVersionedValue).onSuccess(success -> {
                    if (success) {
                        System.out.println("Persisted PaxosState: " + paxosState);
                    } else {
                        System.err.println("Failed to persist PaxosState: " + paxosState);
                    }
                }).onFailure(error -> {
                    System.err.println("Failed to persist PaxosState: " + error.getMessage());
                });
            } catch (Exception e) {
                System.err.println("Failed to serialize PaxosState: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Failed to persist state: " + e.getMessage());
        }
    }
    
    @Override
    public String toString() {
        return "PaxosReplica{" +
                "address=" + getNetworkAddress() +
                ", generation=" + generationCounter.get() +
                ", state=" + paxosState +
                '}';
    }
} 