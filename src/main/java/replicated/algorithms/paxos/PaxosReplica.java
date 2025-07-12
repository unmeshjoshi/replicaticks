package replicated.algorithms.paxos;

import replicated.messaging.*;
import replicated.network.MessageContext;
import replicated.replica.Replica;
import replicated.storage.Storage;
import replicated.storage.VersionedValue;
import replicated.future.ListenableFuture;

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
    
    // State loading tracking
    private volatile boolean stateLoaded = false;
    
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
        
        // Load persistent state asynchronously
        loadState().onSuccess(result -> {
            stateLoaded = true;
            System.out.println("PaxosReplica state loading completed for " + getNetworkAddress());
        }).onFailure(error -> {
            System.err.println("Failed to load state for " + getNetworkAddress() + ": " + error.getMessage());
            // Even on failure, mark as loaded to allow the replica to function with default state
            stateLoaded = true;
        });
    }
    
    @Override
    public void onMessageReceived(Message message, MessageContext context) {
        // Wait for state loading to complete before processing messages
        if (!stateLoaded) {
            System.out.println("PaxosReplica: Dropping message " + message.messageType() + " - state not loaded yet");
            return;
        }
        
        try {
            MessageType mt = message.messageType();
            if (mt == MessageType.PAXOS_PROPOSE_REQUEST) {
                handleProposeRequest(message, context);
            } else if (mt == MessageType.PAXOS_PREPARE_REQUEST) {
                handlePrepareRequest(message, context);
            } else if (mt == MessageType.PAXOS_PROMISE_RESPONSE) {
                handlePromiseResponse(message);
            } else if (mt == MessageType.PAXOS_ACCEPT_REQUEST) {
                handleAcceptRequest(message, context);
            } else if (mt == MessageType.PAXOS_ACCEPTED_RESPONSE) {
                handleAcceptedResponse(message);
            } else if (mt == MessageType.PAXOS_COMMIT_REQUEST) {
                handleCommitRequest(message, context);
            } else {
                System.out.println("PaxosReplica: Unhandled message type: " + mt);
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
            
            // Persist state before sending response
            persistState().onSuccess(result -> {
                // Send promise with any previously accepted value
                PromiseResponse response;
                if (paxosState.getAcceptedProposal() != null) {
                    response = PromiseResponse.promiseWithAccepted(
                        request.getProposalNumber(),
                        paxosState.getAcceptedProposal(),
                        paxosState.getAcceptedValue(),
                        message.correlationId()
                    );
                } else {
                    response = PromiseResponse.promise(
                        request.getProposalNumber(),
                        message.correlationId()
                    );
                }
                
                Message responseMessage = new Message(
                    getNetworkAddress(), message.source(), MessageType.PAXOS_PROMISE_RESPONSE,
                    serializePayload(response), message.correlationId()
                );
                messageBus.sendMessage(responseMessage);
            }).onFailure(error -> {
                System.err.println("Failed to persist state after promise: " + error.getMessage());
                // Send reject response on persistence failure
                PromiseResponse response = PromiseResponse.reject(
                    request.getProposalNumber(),
                    message.correlationId()
                );
                Message rejectMessage = new Message(
                    getNetworkAddress(), message.source(), MessageType.PAXOS_PROMISE_RESPONSE,
                    serializePayload(response), message.correlationId()
                );
                messageBus.sendMessage(rejectMessage);
            });
        } else {
            // Reject the prepare request
            PromiseResponse response = PromiseResponse.reject(
                request.getProposalNumber(),
                message.correlationId()
            );
            Message rejectMessage = new Message(
                getNetworkAddress(), message.source(), MessageType.PAXOS_PROMISE_RESPONSE,
                serializePayload(response), message.correlationId()
            );
            messageBus.sendMessage(rejectMessage);
        }
    }
    
    /**
     * Handle PROMISE response (Phase 1b) - Proposer role.
     */
    private void handlePromiseResponse(Message message) {
        PromiseResponse response = deserializePayload(message.payload(), PromiseResponse.class);
        
        System.out.println("PaxosReplica: Processing PROMISE response - promised: " + response.isPromised() +
                ", correlationId: " + response.getCorrelationId() + ", from: " + message.source());
        
        // Route the response to the RequestWaitingList
        waitingList.handleResponse(message.correlationId(), response, message.source());
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
            
            // Persist state before sending response
            persistState().onSuccess(result -> {
                // Send accepted response
                AcceptedResponse response = AcceptedResponse.accept(
                    request.getProposalNumber(),
                    message.correlationId()
                );
                Message acceptMessage = new Message(
                    getNetworkAddress(), message.source(), MessageType.PAXOS_ACCEPTED_RESPONSE,
                    serializePayload(response), message.correlationId()
                );
                messageBus.sendMessage(acceptMessage);
            }).onFailure(error -> {
                System.err.println("Failed to persist state after accept: " + error.getMessage());
                // Send reject response on persistence failure
                AcceptedResponse response = AcceptedResponse.reject(
                    request.getProposalNumber(),
                    message.correlationId()
                );
                Message rejectMessage = new Message(
                    getNetworkAddress(), message.source(), MessageType.PAXOS_ACCEPTED_RESPONSE,
                    serializePayload(response), message.correlationId()
                );
                messageBus.sendMessage(rejectMessage);
            });
        } else {
            // Reject the accept request
            AcceptedResponse response = AcceptedResponse.reject(
                request.getProposalNumber(),
                message.correlationId()
            );
            Message rejectMessage = new Message(
                getNetworkAddress(), message.source(), MessageType.PAXOS_ACCEPTED_RESPONSE,
                serializePayload(response), message.correlationId()
            );
            messageBus.sendMessage(rejectMessage);
        }
    }
    
    /**
     * Handle ACCEPTED response (Phase 2b) - Proposer role.
     */
    private void handleAcceptedResponse(Message message) {
        AcceptedResponse response = deserializePayload(message.payload(), AcceptedResponse.class);
        
        System.out.println("PaxosReplica: Processing ACCEPTED response - accepted: " + response.isAccepted() +
                ", correlationId: " + response.getCorrelationId() + ", from: " + message.source());
        
        // Route the response to the RequestWaitingList
        waitingList.handleResponse(message.correlationId(), response, message.source());
    }
    
    // === PAXOS COMMIT PHASE ===
    
    /**
     * Handle COMMIT request - Learner role.
     */
    private void handleCommitRequest(Message message, MessageContext context) {
        CommitRequest request = deserializePayload(message.payload(), CommitRequest.class);
        
        // Commit the value and execute the request
        paxosState.commitValue(request.getValue());
        
        // Persist state before executing
        persistState().onSuccess(result -> {
            // Execute the committed request
            ExecutableRequest executableRequest = ExecutableRequest.fromBytes(request.getValue());
            byte[] executionResult = executableRequest.execute();
            
            System.out.println("Committed and executed: " + executableRequest + " -> " + new String(executionResult));
            
            // If this was our proposal, respond to the client
            ProposalNumber ourProposal = activeProposals.get(request.getCorrelationId());
            if (ourProposal != null && ourProposal.equals(request.getProposalNumber())) {
                // Send success response to client
                ProposeResponse response = ProposeResponse.success(executionResult, request.getCorrelationId());
                // TODO: Send to original client (need to track client address)
                System.out.println("Would send success response: " + response);
            }
        }).onFailure(error -> {
            System.err.println("Failed to persist state after commit: " + error.getMessage());
            // Still execute even if persistence fails, but log the error
            ExecutableRequest executableRequest = ExecutableRequest.fromBytes(request.getValue());
            byte[] executionResult = executableRequest.execute();
            
            System.out.println("Committed and executed (persistence failed): " + executableRequest + " -> " + new String(executionResult));
        });
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
        
        // Persist the updated generation counter before proceeding
        persistState().onSuccess(result -> {
            // Create quorum callback for Phase 1 (PREPARE/PROMISE)
            AsyncQuorumCallback<PromiseResponse> quorumCallback = createPrepareQuorumCallback(proposalNumber, value, correlationId, context);
            
            // Store proposal context for response handling  
            ProposalContext proposalContext = new ProposalContext(proposalNumber, value, correlationId, context, quorumCallback);
            activeProposalContexts.put(correlationId, proposalContext);
            
            // Start Phase 1: Send PREPARE to all replicas using broadcastToAllReplicas
            System.out.println("Started Paxos proposal " + proposalNumber + " for value: " + new String(value));
            
            broadcastToAllReplicas(quorumCallback, (node, internalCorrelationId) -> {
                PrepareRequest prepareRequest = new PrepareRequest(proposalNumber, correlationId);
                return new Message(
                    getNetworkAddress(), node, MessageType.PAXOS_PREPARE_REQUEST,
                    serializePayload(prepareRequest), internalCorrelationId
                );
            });
        }).onFailure(error -> {
            System.err.println("Failed to persist generation counter: " + error.getMessage());
            // Clean up and send failure response
            activeProposals.remove(correlationId);
            sendClientFailureResponse(correlationId, context, "Failed to persist generation counter: " + error.getMessage());
        });
    }
    
    /**
     * Create quorum callback for Phase 1 (PREPARE/PROMISE).
     */
    private AsyncQuorumCallback<PromiseResponse> createPrepareQuorumCallback(ProposalNumber proposalNumber, 
                                                                           byte[] value, String correlationId, 
                                                                           MessageContext originalContext) {
        List<NetworkAddress> allNodes = getAllNodes();
        
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
        
        // Start Phase 2: Send ACCEPT to all replicas using broadcastToAllReplicas
        broadcastToAllReplicas(acceptQuorumCallback, (node, internalCorrelationId) -> {
            AcceptRequest acceptRequest = new AcceptRequest(proposalNumber, value, correlationId);
            return new Message(
                getNetworkAddress(), node, MessageType.PAXOS_ACCEPT_REQUEST,
                serializePayload(acceptRequest), internalCorrelationId
            );
        });
        
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
        System.out.println("Starting commit phase for proposal " + proposalNumber);
        
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
     * @return A ListenableFuture that completes when both generation counter and PaxosState are loaded
     */
    private ListenableFuture<Void> loadState() {
        ListenableFuture<Void> future = new ListenableFuture<>();
        
        try {
            // Track completion of both operations
            final boolean[] generationLoaded = {false};
            final boolean[] stateLoaded = {false};
            final Throwable[] errors = {null};
            
            // Load generation counter asynchronously
            storage.get(GENERATION_COUNTER_KEY.getBytes()).onSuccess(versionedValue -> {
                synchronized (future) {
                    if (versionedValue != null) {
                        long savedGeneration = Long.parseLong(new String(versionedValue.value()));
                        generationCounter.set(savedGeneration);
                        System.out.println("Loaded generation counter: " + generationCounter.get());
                    } else {
                        System.out.println("No previous generation counter found, starting with 0");
                    }
                    generationLoaded[0] = true;
                    checkLoadCompletion(future, generationLoaded, stateLoaded, errors);
                }
            }).onFailure(error -> {
                synchronized (future) {
                    System.err.println("Failed to load generation counter: " + error.getMessage());
                    errors[0] = error;
                    generationLoaded[0] = true;
                    checkLoadCompletion(future, generationLoaded, stateLoaded, errors);
                }
            });
            
            // Load PaxosState from storage
            storage.get(PAXOS_STATE_KEY.getBytes()).onSuccess(versionedValue -> {
                synchronized (future) {
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
                            errors[0] = e;
                        }
                    } else {
                        System.out.println("No previous PaxosState found, starting with fresh state");
                    }
                    stateLoaded[0] = true;
                    checkLoadCompletion(future, generationLoaded, stateLoaded, errors);
                }
            }).onFailure(error -> {
                synchronized (future) {
                    System.err.println("Failed to load PaxosState: " + error.getMessage());
                    errors[0] = error;
                    stateLoaded[0] = true;
                    checkLoadCompletion(future, generationLoaded, stateLoaded, errors);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to load state: " + e.getMessage());
            future.fail(e);
        }
        
        return future;
    }
    
    /**
     * Check if both load operations are complete and resolve the future.
     */
    private void checkLoadCompletion(ListenableFuture<Void> future, boolean[] generationLoaded, boolean[] stateLoaded, Throwable[] errors) {
        if (generationLoaded[0] && stateLoaded[0]) {
            if (errors[0] != null) {
                future.fail(errors[0]);
            } else {
                future.complete(null);
            }
        }
    }
    
    /**
     * Persist current state to storage.
     * @return A ListenableFuture that completes when both generation counter and PaxosState are persisted
     */
    private ListenableFuture<Void> persistState() {
        ListenableFuture<Void> future = new ListenableFuture<>();
        
        try {
            // Track completion of both operations
            final boolean[] generationPersisted = {false};
            final boolean[] statePersisted = {false};
            final Throwable[] errors = {null};
            
            // Save generation counter asynchronously
            byte[] generationData = String.valueOf(generationCounter.get()).getBytes();
            VersionedValue generationVersionedValue = new VersionedValue(generationData, System.currentTimeMillis());
            
            storage.set(GENERATION_COUNTER_KEY.getBytes(), generationVersionedValue).onSuccess(success -> {
                synchronized (future) {
                    if (success) {
                        System.out.println("Persisted generation counter: " + generationCounter.get());
                    } else {
                        System.err.println("Failed to persist generation counter: " + generationCounter.get());
                        errors[0] = new RuntimeException("Failed to persist generation counter");
                    }
                    generationPersisted[0] = true;
                    checkCompletion(future, generationPersisted, statePersisted, errors);
                }
            }).onFailure(error -> {
                synchronized (future) {
                    System.err.println("Failed to persist generation counter: " + error.getMessage());
                    errors[0] = error;
                    generationPersisted[0] = true;
                    checkCompletion(future, generationPersisted, statePersisted, errors);
                }
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
                    synchronized (future) {
                        if (success) {
                            System.out.println("Persisted PaxosState: " + paxosState);
                        } else {
                            System.err.println("Failed to persist PaxosState: " + paxosState);
                            errors[0] = new RuntimeException("Failed to persist PaxosState");
                        }
                        statePersisted[0] = true;
                        checkCompletion(future, generationPersisted, statePersisted, errors);
                    }
                }).onFailure(error -> {
                    synchronized (future) {
                        System.err.println("Failed to persist PaxosState: " + error.getMessage());
                        errors[0] = error;
                        statePersisted[0] = true;
                        checkCompletion(future, generationPersisted, statePersisted, errors);
                    }
                });
            } catch (Exception e) {
                synchronized (future) {
                    System.err.println("Failed to serialize PaxosState: " + e.getMessage());
                    errors[0] = e;
                    statePersisted[0] = true;
                    checkCompletion(future, generationPersisted, statePersisted, errors);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to persist state: " + e.getMessage());
            future.fail(e);
        }
        
        return future;
    }
    
    /**
     * Check if both persistence operations are complete and resolve the future.
     */
    private void checkCompletion(ListenableFuture<Void> future, boolean[] generationPersisted, boolean[] statePersisted, Throwable[] errors) {
        if (generationPersisted[0] && statePersisted[0]) {
            if (errors[0] != null) {
                future.fail(errors[0]);
            } else {
                future.complete(null);
            }
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