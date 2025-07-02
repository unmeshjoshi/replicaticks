# Deterministic Simulation Project - Development Plan

## Core Design Principles 🎯

* **Single-Threaded Event Loop:** Driven by master thread calling `tick()` methods in specific order
* **Determinism:** Achieved through single-threaded execution, simulated I/O, and seeded random generators  
* **Asynchronous, Non-Blocking I/O:** All operations return `ListenableFuture<T>`, no blocking calls

---

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

**Phase 1 Complete! Test Coverage:** 53/53 tests passing ✅

---

## Phase 2: Network Layer ⏳ **← NEXT UP!**

- [ ] **Network** interface (`send()`, `register()`, `tick()`)
- [ ] **SimulatedNetwork** implementation with:
  - [ ] Constructor (config, seeded Random)
  - [ ] Internal packet queue with delivery delays
  - [ ] `send()` method creating Packet objects  
  - [ ] `tick()` method processing deliveries + packet loss simulation

---

## Phase 3: MessageBus Layer ⏳

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
├── storage/
│   └── VersionedValue.java       ✅ (value + timestamp with proper byte[] equality)
└── replica/
    └── Replica.java              ✅ (name, address, peers + tick method)
```

### 🧪 **Test Coverage: 53/53 Passing**
- NetworkAddress: 6 tests (creation, equality, port validation)
- MessageType: 1 test (enum completeness)
- Message: 6 tests (creation, equality, null validation)  
- MessageCodec: 8 tests (encoding, decoding, error handling)
- GetRequest: 3 tests (creation, equality, validation)
- SetRequest: 4 tests (creation, equality, validation)
- GetResponse: 4 tests (creation, equality, validation)  
- SetResponse: 3 tests (creation, equality, validation)
- MessagePayloadSerialization: 4 tests (type-safe messaging patterns)
- **VersionedValue: 8 tests (creation, equality, validation, byte[] handling)**
- **Replica: 7 tests (creation, equality, validation, tick method)**

### 🚀 **Next Recommended Steps**
1. **Phase 2: Network Layer** - Implement Network interface and SimulatedNetwork for message passing infrastructure
2. **Phase 2: Message routing** - Enable Replicas to communicate through simulated network with tick-based event loop  
3. **Phase 5: ListenableFuture** - Implement early since it's needed by Storage layer
4. **Phase 3: MessageBus Layer** - Higher-level message routing and codec integration

---

## Development Methodology

Following **TDD cycle**: Red → Green → Refactor  
Following **Tidy First**: Structural changes separate from behavioral changes  
**Commit discipline**: Only when all tests pass, clear behavioral vs structural messages  
**Code quality**: Eliminate duplication, express intent clearly, simplest solution that works 