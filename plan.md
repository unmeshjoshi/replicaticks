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

## Phase 10: Simulation Driver ⏳ **← NEXT**

- [ ] **SimulationDriver** class:
  - [ ] `main()` method with complete setup
  - [ ] Dependency injection (Random, Network, Storage, MessageBus, Replicas, Clients)
  - [ ] Simulation loop with correct tick() ordering

---

## Current Implementation Status

### 📁 **Project Structure**
```
src/main/java/replicated/
├── messaging/
│   ├── NetworkAddress.java         ✅ (with port validation)
│   ├── MessageType.java           ✅ (7 message types)  
│   ├── Message.java              ✅ (with null validation & proper equals)
│   ├── MessageCodec.java         ✅ (interface)
│   ├── JsonMessageCodec.java     ✅ (simplified, no custom serializers)
│   ├── MessageHandler.java       ✅ (interface for message recipients)
│   ├── MessageBus.java           ✅ (higher-level messaging with routing & broadcast)
│   ├── GetRequest.java           ✅ (client request)
│   ├── SetRequest.java           ✅ (client request)  
│   ├── GetResponse.java          ✅ (server response)
│   ├── SetResponse.java          ✅ (server response)
│   ├── InternalGetRequest.java   ✅ (internal distributed request)
│   ├── InternalSetRequest.java   ✅ (internal distributed request)
│   ├── InternalGetResponse.java  ✅ (internal distributed response)
│   └── InternalSetResponse.java  ✅ (internal distributed response)
├── network/
│   ├── Network.java              ✅ (interface with comprehensive documentation + partitioning methods)
│   ├── SimulatedNetwork.java     ✅ (PriorityQueue, domain-driven refactoring, partitioning, per-link config)
│   └── NioNetwork.java           ✅ (production NIO implementation with bug fixes)
├── storage/
│   ├── VersionedValue.java       ✅ (value + timestamp with proper byte[] equality)
│   ├── Storage.java              ✅ (async interface with ListenableFuture)
│   ├── BytesKey.java             ✅ (byte[] wrapper with defensive copying + proper Map key behavior)
│   ├── SimulatedStorage.java     ✅ (async storage with delays, failures, PriorityQueue)
│   └── RocksDbStorage.java       ✅ (production persistent storage with RocksDB)
├── future/
│   └── ListenableFuture.java     ✅ (single-threaded async with callbacks, states, multiple handlers)
├── replica/
│   ├── Replica.java              ✅ (abstract base class with common building blocks)
│   └── QuorumBasedReplica.java   ✅ (quorum-specific consensus extending Replica)
└── client/
    └── Client.java               ✅ (async requests, response handling, timeout management, correlation tracking)

src/test/java/replicated/integration/
├── DistributedSystemIntegrationTest.java ✅ (comprehensive real-world distributed scenarios)
├── SimpleNioIntegrationTest.java         ✅ (basic NIO network testing)
└── ProductionQuorumIntegrationTest.java  ✅ (full production stack: NIO + RocksDB + Quorum)
```

### 🧪 **Test Coverage: 220+ Passing**
- NetworkAddress: 6 tests (creation, equality, port validation)
- MessageType: 1 test (enum completeness)
- Message: 6 tests (creation, equality, null validation)  
- MessageCodec: 8 tests (encoding, decoding, error handling)
- **Client API Messages:**
  - GetRequest: 3 tests (clean client API, no correlation IDs)
  - SetRequest: 4 tests (clean client API, no internal fields)
  - GetResponse: 5 tests (VersionedValue support, clean client responses)  
  - SetResponse: 3 tests (simple success/failure responses)
- **Internal API Messages:**
  - InternalGetRequest: 4 tests (correlation ID tracking)
  - InternalSetRequest: 5 tests (correlation ID + timestamp)
  - InternalGetResponse: 5 tests (correlation ID + VersionedValue)
  - InternalSetResponse: 5 tests (correlation ID + success tracking)
- MessagePayloadSerialization: 4 tests (type-safe messaging patterns)
- **MessageBus: 14 tests (component registration, message routing, broadcast, handler management, tick coordination)**
- **ListenableFuture: 20 tests (states, callbacks, error handling, multiple handlers, validation)**
- **Storage & BytesKey: 21 tests (async operations, defensive copying, Map key behavior, fault injection)**
- VersionedValue: 8 tests (creation, equality, validation, byte[] handling)
- **Replica Architecture Tests:**
  - ReplicaBaseTest: 12 tests (common building blocks, timeout handling, request ID generation)
  - QuorumBasedReplicaTest: 13 tests (quorum logic, distributed consensus, message routing)
  - ReplicaTest: 7 tests (basic abstract replica functionality)
- **Client: 14 tests (async requests, response handling, timeout management, correlation tracking, clean API)**
- **SimulatedNetwork: 14 tests (send/receive, delays, packet loss, deterministic behavior, partitioning, per-link config)**
  - **ENHANCED**: Advanced network simulation with partitioning, asymmetric conditions, and domain-driven refactoring
  - **PERFORMANCE**: Upgraded to PriorityQueue for O(log n) message processing efficiency
- **🆕 DistributedSystemIntegration: 8 tests (comprehensive real-world scenarios)**
  - **End-to-End Operations**: Full distributed GET/SET with 5-replica quorum
  - **Network Partitions**: Split-brain scenarios, majority/minority handling  
  - **Replica Failures**: Byzantine fault tolerance, automatic failover
  - **Concurrent Operations**: High-contention multi-client scenarios
  - **Quorum Requirements**: W+R > N consistency model validation
  - **Conflict Resolution**: Partition healing, consistency recovery
  - **Eventual Consistency**: Convergence demonstration, anti-entropy
  - **Read Repair**: Stale data detection, background synchronization

- **🆕 Production NIO Network: 12 tests (production-ready networking)**
  - **NioNetworkTest**: Socket management, connection handling, message transmission
  - **SimpleNioIntegrationTest**: End-to-end messaging scenarios with real networking
  - **Multiple Message Handling**: TCP framing, message queuing, connection establishment

- **🆕 Production RocksDB Storage: 8 tests (persistent storage)**
  - **RocksDbStorageTest**: Database operations, key-value persistence, async behavior
  - **ProductionQuorumIntegrationTest**: Full production stack with NIO + RocksDB + Quorum consensus

### 🚀 **Current Status: Complete Production-Ready Distributed System**
- **Phases 1-9 Complete**: Full distributed system with clean architecture and extensible replica framework
- **Dual Implementation Strategy**: Both simulation (deterministic testing) and production (real networking) versions
- **Production Stack**: NIO networking + RocksDB persistence + Quorum consensus working reliably
- **Comprehensive Bug Fixes**: Message queuing, TCP framing, test isolation all resolved
- **Advanced Features**: Network partitions, replica failures, quorum consensus, conflict resolution
- **Deterministic Testing**: All scenarios reproducible with consistent results
- **Architecture Ready**: Easy to implement Raft, Chain Replication, Byzantine Fault Tolerance, etc.
- **Next**: Phase 10 - Simulation Driver for complete system orchestration

---

## Development Methodology

Following **TDD cycle**: Red → Green → Refactor  
Following **Tidy First**: Structural changes separate from behavioral changes  
**Commit discipline**: Only when all tests pass, clear behavioral vs structural messages  
**Code quality**: Eliminate duplication, express intent clearly, simplest solution that works 