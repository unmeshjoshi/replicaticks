# Deterministic Simulation Project - Development Plan

## Project Overview & Rationale 🎯

This project implements a **deterministic simulation** of a distributed key-value store with quorum-based consensus. The system simulates network failures, storage delays, and other real-world conditions in a completely deterministic and reproducible manner.

### **Core Design Principles**

The entire codebase is built on these fundamental principles that drive all architectural decisions:

* **Single-Threaded Event Loop:** The system is driven by a single master thread that calls `tick()` methods in a specific order. This eliminates the need for locks and prevents race conditions. All callbacks must be executed sequentially.

* **Determinism:** The goal is a deterministic simulation. This is achieved by single-threaded execution, simulated I/O, and the use of a seeded random number generator. Every run with the same seed produces identical behavior.

*On the Name tick
The name tick is intentionally chosen to evoke the image of a clock's second hand. Each call to tick() represents one discrete, indivisible moment in a "simulation clock."

*Two Roles of tick()
Service Layer (Network, Storage): The Event Loop Driver

The tick() method on these layers is reactive. It processes I/O operations that have just completed.

Application Layer (Replica, Client): The Proactive Logic Driver

The tick() method on these components is proactive. It is used for self-initiated actions like sending a periodic heartbeat or checking for timeouts.

*How This Ensures Determinism
Our design achieves determinism by systematically eliminating the primary sources of randomness found in typical distributed applications.

*Eliminates OS Thread Scheduling Non-determinism: Our design uses a single master thread. The main simulation loop becomes the only "scheduler," dictating the exact, repeatable order of all tick() calls and subsequent callbacks, eliminating race conditions.

*Eliminates Network & I/O Non-determinism: Our SimulatedNetwork and SimulatedStorage replace the unpredictable timing of real-world I/O. They use internal, deterministic queues based on a simulated clock, ensuring events always complete at the same tick and in the same order.

*Controls Randomness with a Seed: Some simulated faults, like packet loss or I/O errors, are probabilistic. To control this, we will use a pseudo-random number generator (e.g., Java's Random class) initialized with a specific seed at the start of each test run. By using the same seed, the generator produces the exact same sequence of "random" numbers, making probabilistic events completely repeatable.

* **Asynchronous, Non-Blocking I/O:** All I/O operations (network and storage) are asynchronous and must not block the event loop. We use a custom `ListenableFuture` for this, ensuring the simulation remains deterministic and efficient.  We can not use Java CompletableFuture because it provides a blocking get method and also has methods like supplyAsync which use a separate thread pool.



## Phase 1: Foundational Models & Codecs

### ✅ **COMPLETED** 
- [x] **NetworkAddress** record (`String ipAddress`, `int port`) + port validation (1-65535)
- [x] **MessageType** enum (7 types: CLIENT_GET/SET_REQUEST/RESPONSE, INTERNAL_GET/SET_REQUEST/RESPONSE)  
- [x] **Message** record (`NetworkAddress source/destination`, `MessageType`, `byte[] payload`) + null validation
- [x] **MessageCodec** interface (`encode(Message)`, `decode(byte[])`)
- [x] **JsonMessageCodec** implementation (Jackson-based, simplified without custom serializers)

### ✅ **BONUS: Structured Payload Types**
- [x] **GetRequest** record (`String key`) + validation
- [x] **SetRequest** record (`String key`, `byte[] value`) + validation  
- [x] **GetResponse** record (`String key`, `byte[] value`, `boolean found`) + validation
- [x] **SetResponse** record (`String key`, `boolean success`) + validation
- [x] **MessagePayloadSerializationTest** demonstrating type-safe messaging patterns

### ✅ **RECENTLY COMPLETED**
- [x] **VersionedValue** record (`byte[] value`, `long timestamp`) + null validation & proper byte[] equality
- [x] **Replica** class (basic properties: `String name`, `NetworkAddress`, `List<NetworkAddress> peers`) + tick() method
- [x] **Network** interface with `send()`, `receive()`, `tick()` methods + comprehensive documentation
- [x] **SimulatedNetwork** class with configurable delays, packet loss, deterministic behavior

**Phase 1 & 2 Complete! (2A + 2B + 2C):** 67/67 tests passing ✅ (advanced network simulation with comprehensive refactoring)

---

## Phase 2: Network Layer ✅ **COMPLETED** 

### Phase 2A: Basic Network Implementation ✅
- [x] **Network** interface (`send()`, `receive()`, `tick()`) with comprehensive documentation
- [x] **SimulatedNetwork** implementation with:
  - [x] Constructor (config, seeded Random, configurable delays and packet loss)
  - [x] Internal packet queue with deterministic delivery timing
  - [x] `send()` method with packet loss simulation using seeded Random
  - [x] `tick()` method with detailed reactive Service Layer documentation
  - [x] Deterministic behavior with identical results for same seeds

### Phase 2B: Advanced Network Simulation ✅ **COMPLETED**
- [x] **Network Partitioning Support**:
  - [x] `partition(NetworkAddress source, NetworkAddress destination)` - bidirectional partition
  - [x] `partitionOneWay(NetworkAddress source, NetworkAddress destination)` - unidirectional partition  
  - [x] `healPartition(NetworkAddress source, NetworkAddress destination)` - restore connectivity
- [x] **Per-Link Configuration**:
  - [x] `setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks)` - specific link delays (implementation complete)
  - [x] `setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate)` - per-link packet loss
- [x] **Partition Management**:
  - [x] Track partitioned links in internal state
  - [x] Apply partitions during message sending (drop partitioned messages)
  - [x] Support asymmetric network conditions (A→B works, B→A blocked)

### Phase 2C: Advanced Refactoring & Code Quality ✅ **COMPLETED**
- [x] **Domain-Driven Design Improvements**:
  - [x] Extracted intention-revealing methods from `send()`: `validateMessage()`, `linkFrom()`, `shouldDropPacket()`, `calculateDeliveryTick()`
  - [x] Enhanced `NetworkLink` record with domain behavior: `isPartitioned()` method
  - [x] Explicit dependencies: `deliverPendingMessagesFor(tickTime)` parameter
- [x] **Performance & Algorithm Enhancements**:
  - [x] Replaced `LinkedList` with `PriorityQueue` for message ordering by delivery time
  - [x] Implemented `Comparable<QueuedMessage>` for automatic priority ordering
  - [x] Improved efficiency from O(n) to O(log n) for message processing
- [x] **Naming & Domain Language**:
  - [x] Renamed `removePartition()` → `healPartition()` (correct distributed systems terminology)
  - [x] Renamed `oneWayPartition()` → `partitionOneWay()` (natural language flow)
  - [x] Renamed `isPacketLost()` → `shouldDropPacket()` (intention-revealing decision method)

### Phase 2D: Production NIO Network Implementation ✅ **COMPLETED**
- [x] **NioNetwork** production-ready implementation with Java NIO:
  - [x] Non-blocking I/O using NIO channels and Selector
  - [x] Multiple server socket binding for different addresses  
  - [x] Deterministic tick() behavior for simulation compatibility
  - [x] Network partitioning support for testing distributed scenarios
  - [x] Thread-safe concurrent access with proper resource management

- [x] **🔧 CRITICAL BUG FIXES COMPLETED**:
  - [x] **Message Queuing System**: Messages now properly queued when connections aren't ready (instead of being dropped)
  - [x] **Channel Management**: Fixed multiple channel creation issue by using `isOpen()` instead of `isConnected()` 
  - [x] **Multiple Message Handling**: Fixed TCP message framing to decode all messages in a single buffer (not just the first)
  - [x] **Test Isolation**: Fixed flaky integration tests by improving state cleanup between test runs

- [x] **Production Features**:
  - [x] Async connection establishment with proper state management
  - [x] Partial write handling with selector-based event management  
  - [x] Message queuing for connection-pending scenarios
  - [x] Comprehensive error handling and resource cleanup
  - [x] Debug logging for troubleshooting complex distributed scenarios

- [x] **Integration Testing**:
  - [x] `SimpleNioIntegrationTest`: 3 tests for basic NIO messaging scenarios
  - [x] `ProductionQuorumIntegrationTest`: 7 tests for production quorum with RocksDB storage  
  - [x] **All NIO tests now passing**: Fixed the "1 out of 3 messages" issue and flaky behavior
  - [x] **End-to-End Production Stack**: Full NIO + RocksDB + Quorum consensus working reliably

---

## Phase 3: MessageBus Layer ✅ **COMPLETED**

- [x] **MessageBus** class:
  - [x] Constructor (Network, MessageCodec dependencies)
  - [x] `sendMessage(Message)` method  
  - [x] `registerHandler()/unregisterHandler()` for component registration
  - [x] `tick()` method with automatic message routing to registered handlers
  - [x] `broadcast()` method for multicast messaging
  - [x] Complete message routing and handler management system

---

## Phase 4: Storage Layer ✅ **COMPLETED**

- [x] **Storage** interface (`ListenableFuture<VersionedValue> get()`, `ListenableFuture<Boolean> set()`, `tick()`)
- [x] **BytesKey** record (wraps `byte[]` with proper equals/hashCode for Map keys + defensive copying)
- [x] **SimulatedStorage** implementation:
  - [x] Constructor (fault config, seeded Random)
  - [x] Async `get()`/`set()` methods returning futures
  - [x] `tick()` method completing queued operations + failure simulation
  - [x] PriorityQueue for efficient operation ordering by completion time
  - [x] Configurable delays and failure injection for testing

---

## Phase 5: ListenableFuture Implementation ✅ **COMPLETED**

- [x] **ListenableFuture<T>** class:
  - [x] Single-threaded safe design (no blocking/external threads)
  - [x] States: PENDING, SUCCEEDED, FAILED
  - [x] `complete(T)`, `fail(Throwable)`, `onSuccess(Consumer<T>)` methods
  - [x] `onFailure(Consumer<Throwable>)` method for error handling
  - [x] Multiple callback support with immediate execution if already completed
  - [x] Proper state transition validation and error handling

---

## Phase 6: Replica Quorum Logic ✅ **COMPLETED**

- [x] Enhance **Replica** class:
  - [x] Add Storage reference and quorum tracking (`Map<CorrelationId, QuorumState>`)
  - [x] `onMessageReceived()` router method implementing MessageHandler interface
  - [x] CLIENT_GET_REQUEST & CLIENT_SET_REQUEST handlers (coordinator role)
  - [x] INTERNAL_GET_REQUEST & INTERNAL_SET_REQUEST handlers (participant role) 
  - [x] INTERNAL_GET_RESPONSE & INTERNAL_SET_RESPONSE handlers (coordinator role)
  - [x] `tick()` method for request timeouts and cleanup
  - [x] Correlation ID generation and unique tracking across distributed operations
  - [x] Quorum calculation (majority) and response aggregation logic
  - [x] Timeout handling with configurable timeout ticks

### ✅ **MAJOR ARCHITECTURAL IMPROVEMENT: Clean Message API Separation**
- [x] **Client API Messages**: GetRequest/SetRequest/GetResponse/SetResponse (clean, no internal concerns)
- [x] **Internal API Messages**: Internal* versions with explicit `correlationId` fields for distributed tracking
- [x] **Type Safety**: Prevents mixing client-facing and internal distributed system messages
- [x] **Separation of Concerns**: Client API stays simple while internal API handles distributed complexity
- [x] **19 additional tests** ensuring comprehensive coverage of both API layers

---

## Phase 7: Client Implementation ✅ **COMPLETED**

- [x] **Client** class:
  - [x] Pending requests tracking (`Map<CorrelationId, ListenableFuture>`)
  - [x] `sendGetRequest()` and `sendSetRequest()` methods returning ListenableFuture
  - [x] `onMessageReceived()` response handler implementing MessageHandler interface
  - [x] `tick()` method for request timeouts and cleanup
  - [x] Correlation ID generation for unique request tracking
  - [x] Configurable timeout handling with async future completion
  - [x] Clean client API using GetRequest/SetRequest/GetResponse/SetResponse
  - [x] Request/response matching by key for client-side correlation
  - [x] Comprehensive error handling and timeout management

---

## Phase 8: Advanced Integration Testing ✅ **COMPLETED**

### ✅ **COMPREHENSIVE DISTRIBUTED SYSTEM SCENARIOS**
- [x] **End-to-End Operations**: Full Client → Replica → Storage → Network simulation with quorum consensus
- [x] **Network Partition Tolerance**: Split-brain scenarios, majority/minority partition handling (CAP theorem)
- [x] **Replica Failure Handling**: Graceful degradation, automatic failover, continued operation
- [x] **Concurrent Operations**: Multiple clients, proper serialization, high-throughput scenarios
- [x] **Quorum Requirements**: W+R > N consistency model, prevents split-brain writes
- [x] **Conflict Resolution**: Last-write-wins with timestamps, automatic partition healing
- [x] **Eventual Consistency**: Convergence demonstration, anti-entropy mechanisms
- [x] **Read Repair**: Automatic stale data detection, background synchronization

### ✅ **REAL-WORLD DISTRIBUTED SCENARIOS**
- [x] **`shouldPerformEndToEndGetSetOperation()`**: Basic distributed GET/SET with 5-replica quorum
- [x] **`shouldHandleNetworkPartition()`**: Partition tolerance with 3-vs-2 replica splits
- [x] **`shouldHandleReplicaFailure()`**: Byzantine fault tolerance with replica removal
- [x] **`shouldHandleConcurrentOperations()`**: High-contention concurrent client operations
- [x] **`shouldDemonstrateQuorumRequirements()`**: Quorum consensus validation
- [x] **`shouldHandleConflictResolution()`**: Partition healing and consistency recovery
- [x] **`shouldDemonstrateEventualConsistency()`**: Convergence after network partitions
- [x] **`shouldHandleReadRepairScenario()`**: Read-time consistency repairs

### ✅ **TECHNICAL ACHIEVEMENTS**
- [x] **Enhanced Replica**: Added `storage.tick()` integration for proper storage processing
- [x] **Comprehensive Test Framework**: `DistributedSystemIntegrationTest` with 8 advanced scenarios
- [x] **Deterministic Simulation**: All scenarios reproducible with consistent results
- [x] **Production-Ready Features**: Handles real-world failure scenarios comprehensively

---

## Phase 9: Clean Architecture - Common Building Blocks ✅ **COMPLETED**

### ✅ **ARCHITECTURAL INSIGHT: Separation of Common vs Algorithm-Specific Logic**
**Problem**: The original `Replica` class contained both common building blocks (shared across all replication algorithms) and quorum-specific logic (only relevant to quorum-based consensus).

**Solution**: Extracted common building blocks into a reusable architecture that supports multiple replication algorithms (Raft, Chain Replication, Byzantine Fault Tolerance, etc.).

### ✅ **ARCHITECTURAL REFACTORING COMPLETED**
- [x] **`Replica` (Abstract Base Class)**:
  - [x] Common building blocks: replica identity, request ID generation, timeout management
  - [x] Message routing dispatcher (`onMessageReceived()`)
  - [x] Storage and MessageBus integration
  - [x] Serialization/deserialization utilities
  - [x] Basic `tick()` lifecycle management
  - [x] Abstract `PendingRequest` base class for request tracking

- [x] **`QuorumBasedReplica extends Replica`**:
  - [x] Quorum-specific consensus logic (QuorumState, majority calculation)
  - [x] Coordinator/participant role handling
  - [x] Quorum response aggregation and conflict resolution
  - [x] Timestamp-based ordering for distributed consistency
  - [x] All existing quorum functionality preserved

- [x] **Clean Test Architecture**:
  - [x] `ReplicaBaseTest` - Tests common building blocks (12 tests)
  - [x] `QuorumBasedReplicaTest` - Tests quorum-specific logic (renamed from `EnhancedReplicaTest`)
  - [x] `ReplicaTest` - Tests basic abstract replica functionality

### ✅ **ARCHITECTURAL BENEFITS ACHIEVED**
- [x] **Reusability**: Common building blocks can be shared across different replication algorithms
- [x] **Maintainability**: Clear separation of concerns between infrastructure and algorithm logic
- [x] **Extensibility**: Easy to implement new replication algorithms (Raft, Chain Replication, etc.)
- [x] **Backward Compatibility**: Existing code continues to work without changes
- [x] **Type Safety**: Proper inheritance hierarchy with abstract base classes

---

## Phase 10: Critical Production Architecture Fixes ✅ **COMPLETED**

### ✅ **MAJOR FIX: Client Connection Architecture**
**Problem**: Client was registering against IP addresses upfront instead of establishing connections per request, violating real-world distributed system patterns.

**Solution**: Implemented industry-standard bootstrap discovery pattern inspired by Kafka and TigerBeetle:
- [x] **Bootstrap Replicas Pattern**: Client constructor now accepts `List<NetworkAddress> bootstrapReplicas`
- [x] **Dynamic Connection Establishment**: `establishConnectionAndSend()` method connects per request
- [x] **Network Layer Abstraction**: Added `establishConnection()` method to Network interface
- [x] **SimulatedNetwork Implementation**: Uses localhost with ephemeral ports for deterministic testing
- [x] **NioNetwork Implementation**: Real socket connections with OS-assigned addresses after blocking connect
- [x] **Failover Support**: Client iterates through bootstrap replicas for automatic failover
- [x] **Connection Pooling Ready**: Architecture supports future connection reuse optimizations

### ✅ **CRITICAL FIX: NIONetwork Bidirectional Communication**
**Problem**: NIONetwork was not properly handling client-side vs server-side connections, causing response delivery issues.

**Solution**: Implemented proper channel management following production networking patterns:
- [x] **Accepted Client Channels**: Added `acceptedClientChannels` map to track server-side connections
- [x] **Enhanced handleAccept()**: Properly stores accepted client connections with remote address keys
- [x] **Updated sendMessageDirectly()**: Checks accepted channels first for sending responses back to clients
- [x] **Extended findDestinationForChannel()**: Searches both outbound and accepted channel types
- [x] **Structural vs Behavioral Separation**: Followed TDD and Tidy First principles
- [x] **Test Validation**: All 220+ tests continue to pass after structural changes

### ✅ **KNOWLEDGE ARTIFACT: "Growing a Language" Article**
**Achievement**: Created comprehensive article demonstrating Guy Steele's "Growing a Language" concept using our development session.

**Content Includes**:
- [x] **7 Architectural Layers**: Foundation → Network → Storage → Messaging → Consensus → Client → Testing
- [x] **Language Evolution**: From raw Java networking to domain-specific distributed systems vocabulary
- [x] **AI as Language Growth Catalyst**: Pattern recognition, architectural guidance, refactoring direction
- [x] **Complex Algorithm Enablement**: How grown language enables LLM implementation of Paxos, Raft, PBFT
- [x] **Industry Pattern Integration**: Real-world examples from Kafka, TigerBeetle, distributed systems

### ✅ **ARCHITECTURAL IMPROVEMENTS**
- [x] **Connection Management**: Proper separation of client/server connection lifecycle
- [x] **Bootstrap Discovery**: Industry-standard cluster discovery and failover patterns
- [x] **Network Abstraction**: Clean interface supporting both simulation and production
- [x] **Production Readiness**: Real networking with proper connection establishment
- [x] **Testing Determinism**: Maintained simulation capabilities for reproducible testing

---

## Phase 11: NIONetwork Critical Client-Server Connection Fixes ⏳ **← NEXT**

### 🆕 Phase 11A: Direct-Channel Responses (in progress)

### Scope
Implement ability for replicas to send a response back on the exact `SocketChannel` that carried the request – first by plumbing support in the network layer.

### ✅ Step 1 – Network API surface
- Added `sendOnChannel(SocketChannel, Message)` default method to `Network` interface.
- Implemented concrete logic in `NioNetwork` that:
  1. Encodes message via `codec`.
  2. Queues bytes in `ChannelState.pendingWrites`.
  3. Registers `OP_WRITE` on selector key so data flushes next tick.

### ⏭️ Next Steps
1. **MessageContext exposure** – add `channel()` getter (already present) and stabilise its use.
2. **MessageBus.reply() helper** – delegate to `network.sendOnChannel()`.
3. **Replica changes** – persist the original `MessageContext` with every pending request and call `messageBus.reply()` instead of `sendMessage()`.
4. **Integration tests** – verify responses arrive in-order on the same socket.

## Phase 12: TigerBeetle-Style Tick Management 🆕 **← CURRENT FOCUS**

### **Problem Statement**
Current tick orchestration is scattered across tests with inconsistent patterns. Each test implements its own `runUntil()` method with different tick management approaches. We need centralized, deterministic tick management following TigerBeetle's proven patterns.

### **TigerBeetle Analysis Results**
After analyzing TigerBeetle's codebase, we discovered:
- ✅ **NO Component interface** - Direct method calls with concrete types
- ✅ **Internal counters only where needed** - Timeout objects, Network, Storage
- ✅ **Simple orchestration** - Main loop calls `replica.tick()`, `network.tick()`, `storage.tick()`
- ✅ **Self-contained timeouts** - Each timeout manages its own tick counter

### **Phase 12A: Structural Changes (Tidy First)** ⏳ **← NEXT**

#### **Step 1: Remove Tick Parameters from Interfaces**
- [ ] **Update Network Interface**: Remove `currentTick` parameter from `tick(long currentTick)` → `tick()`
- [ ] **Update Storage Interface**: Remove `currentTick` parameter from `tick(long currentTick)` → `tick()`
- [ ] **Update Client Interface**: Remove `currentTick` parameter from `tick(long currentTick)` → `tick()`
- [ ] **Update Replica Interface**: Remove `currentTick` parameter from `tick(long currentTick)` → `tick()`

#### **Step 2: Add Internal Counters Where Needed**
- [ ] **SimulatedNetwork**: Add `private long currentTick = 0` and increment in `tick()`
- [ ] **SimulatedStorage**: Add `private long currentTick = 0` and increment in `tick()`
- [ ] **Timeout Objects**: Create `Timeout` class with internal `ticks` counter (TigerBeetle pattern)

#### **Step 3: Create Timeout Management** ✅ **COMPLETED**
- [x] **Timeout Class**: Implement TigerBeetle-style timeout with internal counter
  - [x] `start()` method to begin timeout
  - [x] `tick()` method to increment internal counter
  - [x] `fired()` method to check if timeout expired
  - [x] `reset()` method for retries
  - [x] `stop()` method to stop timeout
  - [x] `getRemainingTicks()` method for debugging
- [x] **TimeoutTest**: Comprehensive test coverage (15 tests)
- [ ] **Update Client**: Replace internal timeout tracking with Timeout objects
- [ ] **Update Replica**: Replace internal timeout tracking with Timeout objects

### **Phase 12B: Behavioral Changes (After Structural)**

#### **Step 4: Create Simulation Driver**
- [ ] **SimulationDriver Class**: Centralized orchestration following TigerBeetle pattern
  - [ ] `List<Client> clients` - Application Layer components
  - [ ] `List<Replica> replicas` - Application Layer components  
  - [ ] `List<Storage> storages` - Service Layer components
  - [ ] `List<Network> networks` - Service Layer components
  - [ ] `runSimulation(int maxTicks)` method with deterministic order

#### **Step 5: Update Test Infrastructure**
- [ ] **TestSimulationDriver**: Extends SimulationDriver for test scenarios
  - [ ] `runUntil(Supplier<Boolean> condition, long timeoutMs)` method
  - [ ] Replace all test `runUntil()` implementations
- [ ] **Update Integration Tests**: Use centralized simulation driver
  - [ ] `ProductionQuorumIntegrationTest`
  - [ ] `DistributedSystemIntegrationTest`
  - [ ] `SimpleNioIntegrationTest`

#### **Step 6: Create Main Entry Point**
- [ ] **SimulationMain Class**: Production simulation entry point
  - [ ] `main(String[] args)` method
  - [ ] Component setup and orchestration
  - [ ] Command-line argument parsing for simulation parameters

### **Phase 12C: Validation & Testing**

#### **Step 7: Comprehensive Testing**
- [ ] **Unit Tests**: Verify Timeout class behavior
- [ ] **Integration Tests**: Verify centralized tick orchestration
- [ ] **Determinism Tests**: Ensure identical results with same seed
- [ ] **Performance Tests**: Verify no regression in simulation speed

#### **Step 8: Documentation & Examples**
- [ ] **Updated README**: Document new tick management approach
- [ ] **Code Examples**: Demonstrate SimulationDriver usage
- [ ] **Migration Guide**: How to update existing tests

### **Expected Benefits**
- ✅ **Simplified API**: No tick parameters to pass around
- ✅ **TigerBeetle Proven**: Follows production distributed system patterns
- ✅ **Centralized Orchestration**: Single source of truth for simulation loop
- ✅ **Better Testing**: Consistent test infrastructure across all scenarios
- ✅ **Production Ready**: Main simulation entry point for real deployments