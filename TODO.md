# TODO: Deterministic Replica Simulation Project

This file outlines the steps to build a modular, testable simulation environment using a layered, push-based architecture with a pluggable storage layer.

### ☐ **Task 1: Define Foundational Models and Codecs**

-   [ ] **Define `NetworkAddress` Structure**
    -   [ ] Add field: `ipAddress` (InetAddress)
    -   [ ] Add field: `port` (Number)

-   [ ] **Define `MessageType` Enum**
    -   [ ] Add client-facing types: `CLIENT_GET_REQUEST`, `CLIENT_SET_REQUEST`, `CLIENT_RESPONSE`.
    -   [ ] Add internal replica-to-replica types: `INTERNAL_GET_REQUEST`, `INTERNAL_GET_RESPONSE`, `INTERNAL_SET_REQUEST`, `INTERNAL_SET_RESPONSE`.

-   [ ] **Define `Message` (The Logical Envelope)**
    -   [ ] Add field: `source` (`NetworkAddress`)
    -   [ ] Add field: `destination` (`NetworkAddress`)
    -   [ ] Add field: `messageType` (`MessageType`)
    -   [ ] Add field: `payload` (`byte[]`)

-   [ ] **Define `Replica` Structure**
    -   [ ] Add field: `name` (String)
    -   [ ] Add field: `networkAddress` (`NetworkAddress`)
    -   [ ] Add field: `peers` (List of `NetworkAddress`)

-   [ ] **Define the `MessageCodec`**
    -   [ ] Create a `MessageCodec` with two methods:
        -   `byte[] encode(Message message)`
        -   `Message decode(byte[] data)`

-   [ ] **Define `VersionedValue` Structure**
    -   [ ] Add field: `value` (`byte[]`)
    -   [ ] Add field: `timestamp` (`long`)

### ☐ **Task 2: Build the Network Layer**

-   [ ] **Define the `Network` Interface**
    -   [ ] Define a method: `send(source: NetworkAddress, destination: NetworkAddress, data: byte[])`
    -   [ ] Define a method: `register(address: NetworkAddress, callbackTarget: Object)`
    -   [ ] Define a method: `tick()` (In `SimulatedNetwork`, this advances the simulation clock. In `NIONetwork`, this polls for ready I/O events).

-   [ ] **Implement `SimulatedNetwork`**
    -   [ ] Add configuration for fault injection (delays, loss).
    -   [ ] Its `tick()` method is its **single-threaded event loop** guaranteeing sequential callback execution for packet delivery.

### ☐ **Task 3: Build the MessageBus Layer**

-   [ ] **Create the `MessageBus` Component**
    -   [ ] It will hold a reference to a `Network` implementation and the `MessageCodec`.

-   [ ] **Implement Core Logic**
    -   [ ] `sendMessage(Message message)`: Encodes the message and calls `network.send()`.
    -   [ ] `onPacketReceived(data: byte[], source: NetworkAddress)`: Decodes the packet into a `Message` and invokes the correct callback on the destination `Replica` or `Client`.

### ☐ **Task 4: Build the Storage Layer**

-   [ ] **Define the `Storage` Interface**
    -   [ ] Define method: `ListenableFuture<VersionedValue> get(byte[] key)`
    -   [ ] Define method: `ListenableFuture<Boolean> set(byte[] key, VersionedValue value)`
    -   [ ] Define method: `tick()`

-   [ ] **Implement `SimulatedStorage`**
    -   [ ] Implement an in-memory `Map`. **Note:** Since `byte[]` cannot be used directly as a map key in Java, wrap it in a class/record (e.g., `BytesKey`) that provides correct `equals()` and `hashCode()` implementations.
    -   [ ] Add configuration for fault injection (e.g., I/O delays, write failures).
    -   [ ] Its `tick()` method is its **event loop**. It checks a pending queue and completes due `ListenableFuture`s.

### ☐ **Task 5: Implement Replica Quorum Logic**

-   [ ] **Define Replica State**
    -   [ ] The `Replica` will hold a reference to a `Storage` implementation.
    -   [ ] Implement a `Map<RequestId, QuorumState>` to track pending client requests.

-   [ ] **Implement `onMessageReceived(Message message)` Logic**
    -   [ ] **Case `CLIENT_REQUEST` (Act as Coordinator):** Create and send an `INTERNAL_*_REQUEST` to all peers and to itself.
    -   [ ] **Case `INTERNAL_*_REQUEST` (Act as Participant):** Call `storage.get()` or `storage.set()`. Attach a callback to the returned `ListenableFuture` to send the `INTERNAL_*_RESPONSE` back to the coordinator.
    -   [ ] **Case `INTERNAL_*_RESPONSE` (Act as Coordinator):** Add the response to the `QuorumState`. If a quorum is met, determine the final result and send the `CLIENT_RESPONSE` back to the original client.

-   [ ] **Implement `tick(currentTick)` method**
    -   [ ] Use for proactive work (e.g., heartbeats) and for timing out pending quorum requests.

### ☐ **Task 6: Implement the Client and ListenableFuture**

-   [ ] **Define a `ListenableFuture` Implementation**
    -   **Note:** Java's `CompletableFuture` is unsuitable for this single-threaded model.
    -   [ ] It must support states: `PENDING`, `SUCCEEDED`, `FAILED`.
    -   [ ] It requires internal methods like `complete(result)` and `fail(error)`.
    -   [ ] It must provide non-blocking callback registration, e.g., `onSuccess(callback)`.

-   [ ] **Create the `Client` Component**
    -   [ ] It will have an internal `Map` to store pending requests, mapping a `CorrelationId` to a `ListenableFuture`.

-   [ ] **Implement `sendRequest(byte[] key, byte[] value)` method**
    -   [ ] Generate a unique `CorrelationId`.
    -   [ ] Create and store a new `ListenableFuture`.
    -   [ ] Create and send a `CLIENT_REQUEST` message containing the `CorrelationId`.
    -   [ ] Return the `ListenableFuture` to the caller.

-   [ ] **Implement `onMessageReceived(Message message)` callback**
    -   [ ] Decode the response to extract the `CorrelationId`.
    -   [ ] Find the corresponding `ListenableFuture` and call `future.complete()` with the result.

-   [ ] **Implement `tick(currentTick)` method**
    -   [ ] Use to manage request timeouts by completing the `ListenableFuture` exceptionally.

### ☐ **Task 7: Implement the Simulation Driver**

-   [ ] **Implement Setup Logic**
    -   [ ] Instantiate `SimulatedNetwork`, `MessageBus`, and `SimulatedStorage`.
    -   [ ] Create `Replica` and `Client` instances.
    -   [ ] Register all participants with the `MessageBus` and `Network`.
    -   [ ] Inject the `SimulatedStorage` instance into each `Replica`.

-   [ ] **Implement the Main Simulation Loop**
    -   [ ] **Inside the loop, maintain the critical order:**
        1.  **Clients & Replicas Tick First**: Call `tick()` on all `Client` and `Replica` instances for any proactive work.
        2.  **Service Layers Tick Second**: Call `tick()` on the `SimulatedStorage` and `SimulatedNetwork` to trigger callbacks for any I/O (storage or network) that is due.