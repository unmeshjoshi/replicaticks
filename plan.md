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

**Phase 1 & 2 Complete! Test Coverage:** 62/62 tests passing ✅

---

## Phase 2: Network Layer ✅ **COMPLETED**

- [x] **Network** interface (`send()`, `receive()`, `tick()`) with comprehensive documentation
- [x] **SimulatedNetwork** implementation with:
  - [x] Constructor (config, seeded Random, configurable delays and packet loss)
  - [x] Internal packet queue with deterministic delivery timing
  - [x] `send()` method with packet loss simulation using seeded Random
  - [x] `tick()` method with detailed reactive Service Layer documentation
  - [x] Deterministic behavior with identical results for same seeds

---

## Phase 3: MessageBus Layer ⏳ **← NEXT UP!**

- [ ] **MessageBus** class:
  - [ ] Constructor (Network, MessageCodec dependencies)
  - [ ] `sendMessage(Message)` method
  - [ ] `onPacketReceived()` callback routing

---

## Phase 4: Storage Layer ⏳

- [ ] **Storage** interface (`ListenableFuture<VersionedValue> get()`, `ListenableFuture<Boolean> set()`, `tick()`)
- [ ] **BytesKey** record (wraps `byte[]` with proper equals/hashCode for Map keys)
- [ ] **SimulatedStorage** implementation:
  - [ ] Constructor (fault config, seeded Random)
  - [ ] Async `get()`/`set()` methods returning futures
  - [ ] `tick()` method completing queued operations + failure simulation

---

## Phase 5: ListenableFuture Implementation ⏳

- [ ] **ListenableFuture<T>** class:
  - [ ] Single-threaded safe design (no blocking/external threads)
  - [ ] States: PENDING, SUCCEEDED, FAILED
  - [ ] `complete(T)`, `fail(Throwable)`, `onSuccess(Consumer<T>)` methods

---

## Phase 6: Replica Quorum Logic ⏳  

- [ ] Enhance **Replica** class:
  - [ ] Add Storage reference and quorum tracking (`Map<RequestId, QuorumState>`)
  - [ ] `onMessageReceived()` router method
  - [ ] CLIENT_REQUEST handler (coordinator role)
  - [ ] INTERNAL_REQUEST handler (participant role) 
  - [ ] INTERNAL_RESPONSE handler (coordinator role)
  - [ ] `tick()` method for heartbeats/timeouts

---

## Phase 7: Client Implementation ⏳

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
│   ├── GetRequest.java           ✅ (client request)
│   ├── SetRequest.java           ✅ (client request)  
│   ├── GetResponse.java          ✅ (server response)
│   └── SetResponse.java          ✅ (server response)
├── network/
│   ├── Network.java              ✅ (interface with comprehensive documentation)
│   └── SimulatedNetwork.java     ✅ (delays, packet loss, deterministic simulation)
├── storage/
│   └── VersionedValue.java       ✅ (value + timestamp with proper byte[] equality)
└── replica/
    └── Replica.java              ✅ (name, address, peers + tick method)
```

### 🧪 **Test Coverage: 62/62 Passing**
- NetworkAddress: 6 tests (creation, equality, port validation)
- MessageType: 1 test (enum completeness)
- Message: 6 tests (creation, equality, null validation)  
- MessageCodec: 8 tests (encoding, decoding, error handling)
- GetRequest: 3 tests (creation, equality, validation)
- SetRequest: 4 tests (creation, equality, validation)
- GetResponse: 4 tests (creation, equality, validation)  
- SetResponse: 3 tests (creation, equality, validation)
- MessagePayloadSerialization: 4 tests (type-safe messaging patterns)
- VersionedValue: 8 tests (creation, equality, validation, byte[] handling)
- Replica: 7 tests (creation, equality, validation, tick method)
- **SimulatedNetwork: 9 tests (send/receive, delays, packet loss, deterministic behavior)**

### 🚀 **Next Recommended Steps**
1. **Phase 3: MessageBus Layer** - Higher-level message routing combining Network and MessageCodec  
2. **Phase 5: ListenableFuture** - Implement early since it's needed by Storage layer (asynchronous operations)
3. **Phase 4: Storage Layer** - SimulatedStorage with BytesKey for deterministic data persistence
4. **Phase 6: Enhanced Replica** - Add message handling and quorum logic using Network + Storage

---

## Development Methodology

Following **TDD cycle**: Red → Green → Refactor  
Following **Tidy First**: Structural changes separate from behavioral changes  
**Commit discipline**: Only when all tests pass, clear behavioral vs structural messages  
**Code quality**: Eliminate duplication, express intent clearly, simplest solution that works 