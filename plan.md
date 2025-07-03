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
  - [x] Add Storage reference and quorum tracking (`Map<RequestId, QuorumState>`)
  - [x] `onMessageReceived()` router method implementing MessageHandler interface
  - [x] CLIENT_GET_REQUEST & CLIENT_SET_REQUEST handlers (coordinator role)
  - [x] INTERNAL_GET_REQUEST & INTERNAL_SET_REQUEST handlers (participant role) 
  - [x] INTERNAL_GET_RESPONSE & INTERNAL_SET_RESPONSE handlers (coordinator role)
  - [x] `tick()` method for request timeouts and cleanup
  - [x] Request ID generation and unique tracking across distributed operations
  - [x] Quorum calculation (majority) and response aggregation logic
  - [x] Timeout handling with configurable timeout ticks

---

## Phase 7: Client Implementation ⏳ **← CURRENT**

- [ ] **Client** class:
  - [ ] Pending requests tracking (`Map<CorrelationId, ListenableFuture>`)
  - [ ] `sendRequest()` method 
  - [ ] `onMessageReceived()` response handler
  - [ ] `tick()` method for request timeouts

---

## Phase 8: Simulation Driver ⏳

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
│   └── SetResponse.java          ✅ (server response)
├── network/
│   ├── Network.java              ✅ (interface with comprehensive documentation + partitioning methods)
│   └── SimulatedNetwork.java     ✅ (PriorityQueue, domain-driven refactoring, partitioning, per-link config)
├── storage/
│   ├── VersionedValue.java       ✅ (value + timestamp with proper byte[] equality)
│   ├── Storage.java              ✅ (async interface with ListenableFuture)
│   ├── BytesKey.java             ✅ (byte[] wrapper with defensive copying + proper Map key behavior)
│   └── SimulatedStorage.java     ✅ (async storage with delays, failures, PriorityQueue)
├── future/
│   └── ListenableFuture.java     ✅ (single-threaded async with callbacks, states, multiple handlers)
└── replica/
    └── Replica.java              ✅ (name, address, peers + tick method)
```

### 🧪 **Test Coverage: 135/135 Passing**
- NetworkAddress: 6 tests (creation, equality, port validation)
- MessageType: 1 test (enum completeness)
- Message: 6 tests (creation, equality, null validation)  
- MessageCodec: 8 tests (encoding, decoding, error handling)
- GetRequest: 3 tests (creation, equality, validation)
- SetRequest: 4 tests (creation, equality, validation)
- GetResponse: 5 tests (creation, equality, validation, requestId support)  
- SetResponse: 3 tests (creation, equality, validation)
- MessagePayloadSerialization: 4 tests (type-safe messaging patterns)
- **MessageBus: 14 tests (component registration, message routing, broadcast, handler management, tick coordination)**
- **ListenableFuture: 20 tests (states, callbacks, error handling, multiple handlers, validation)**
- **Storage & BytesKey: 21 tests (async operations, defensive copying, Map key behavior, fault injection)**
- VersionedValue: 8 tests (creation, equality, validation, byte[] handling)
- Replica: 7 tests (creation, equality, validation, tick method)
- **EnhancedReplica: 13 tests (quorum logic, distributed consensus, message routing, request tracking, timeout handling)**
- **SimulatedNetwork: 14 tests (send/receive, delays, packet loss, deterministic behavior, partitioning, per-link config)**
  - **ENHANCED**: Advanced network simulation with partitioning, asymmetric conditions, and domain-driven refactoring
  - **PERFORMANCE**: Upgraded to PriorityQueue for O(log n) message processing efficiency

### 🚀 **Next Recommended Steps**
1. **Phase 7: Client** - Implement client requests using MessageBus for communication with replicas
2. **Phase 8: Simulation Driver** - Integrate all components with proper tick() ordering
3. **End-to-End Testing** - Full distributed system scenarios with partitions and failures
4. **Advanced Features** - Leader election, read/write quorums, conflict resolution

---

## Development Methodology

Following **TDD cycle**: Red → Green → Refactor  
Following **Tidy First**: Structural changes separate from behavioral changes  
**Commit discipline**: Only when all tests pass, clear behavioral vs structural messages  
**Code quality**: Eliminate duplication, express intent clearly, simplest solution that works 