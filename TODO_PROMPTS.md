# TODO: Deterministic Simulation Project (Prompt-Based)

This document outlines the step-by-step prompts to generate the project components using an LLM.

### **Preamble: Core Design Principles**

**To the LLM:** Before you begin, understand that the entire project is built on these core principles. Keep them in mind for all generated code.

* **Single-Threaded Event Loop:** The system is driven by a single master thread that calls `tick()` methods in a specific order. This eliminates the need for locks and prevents race conditions. All callbacks must be executed sequentially.
* **Determinism:** The goal is a deterministic simulation. This is achieved by single-threaded execution, simulated I/O, and the use of a seeded random number generator.
* **Asynchronous, Non-Blocking I/O:** All I/O operations (network and storage) are asynchronous and must not block the event loop. We will use a custom `ListenableFuture` for this.

---

### **Phase 1: Foundational Models & Codecs**

* **Prompt 1:** Generate a final Java record named `NetworkAddress` with a `String ipAddress` and an `int port`.
* **Prompt 2:** Generate a Java enum named `MessageType` with the following values: `CLIENT_GET_REQUEST`, `CLIENT_SET_REQUEST`, `CLIENT_RESPONSE`, `INTERNAL_GET_REQUEST`, `INTERNAL_GET_RESPONSE`, `INTERNAL_SET_REQUEST`, `INTERNAL_SET_RESPONSE`.
* **Prompt 3:** Generate a final Java record named `VersionedValue` with a `byte[] value` and a `long timestamp`.
* **Prompt 4:** Generate a final Java record named `Message` with a `NetworkAddress source`, a `NetworkAddress destination`, a `MessageType messageType`, and a `byte[] payload`.
* **Prompt 5:** Generate a Java class named `Replica` to hold its static properties. Include fields for `String name`, `NetworkAddress networkAddress`, and `List<NetworkAddress> peers`.
* **Prompt 6:** Generate a Java interface named `MessageCodec` with two methods: `byte[] encode(Message message)` and `Message decode(byte[] data)`.

---

### **Phase 2: The Network Layer**

* **Prompt 7:** Generate a Java interface named `Network` with three methods: `void send(NetworkAddress source, NetworkAddress destination, byte[] data)`, `void register(NetworkAddress address, Object callbackTarget)`, and `void tick()`.
* **Prompt 8:** Generate a Java class named `SimulatedNetwork` that implements the `Network` interface. Its constructor should accept configuration for message loss/delay and a seeded `java.util.Random` instance. It should have an internal queue for pending packets.
* **Prompt 9:** In the `SimulatedNetwork` class, implement the `send` method. It should wrap the incoming data in a private `Packet` object with a calculated `deliveryTick` and add it to the pending queue.
* **Prompt 10:** In the `SimulatedNetwork` class, implement the `tick` method. This method must act as a single-threaded event loop, checking the pending queue for packets whose `deliveryTick` is due, and invoking a callback on the registered target. Use the seeded `Random` instance to decide if a packet should be dropped.

---

### **Phase 3: The MessageBus Layer**

* **Prompt 11:** Generate a Java class named `MessageBus`. Its constructor should accept a `Network` implementation and a `MessageCodec`.
* **Prompt 12:** In the `MessageBus` class, implement a `sendMessage(Message message)` method. It should use the `MessageCodec` to encode the message and then call the `network.send()` method.
* **Prompt 13:** In the `MessageBus` class, implement an `onPacketReceived(byte[] data, NetworkAddress source)` method. It should use the `MessageCodec` to decode the packet and then invoke an `onMessageReceived` callback on the correct registered `Replica` or `Client`.

---

### **Phase 4: The Storage Layer**

* **Prompt 14:** Generate a Java interface named `Storage` with three methods: `ListenableFuture<VersionedValue> get(byte[] key)`, `ListenableFuture<Boolean> set(byte[] key, VersionedValue value)`, and `void tick()`.
* **Prompt 15:** Generate a final Java record named `BytesKey` that wraps a `byte[]`. It must implement `equals()` and `hashCode()` correctly based on the array's content so it can be used as a `Map` key.
* **Prompt 16:** Generate a Java class named `SimulatedStorage` that implements the `Storage` interface. Its constructor should accept fault configuration and a seeded `java.util.Random` instance. It should use a `Map<BytesKey, VersionedValue>` for its internal state.
* **Prompt 17:** In the `SimulatedStorage` class, implement the `get` and `set` methods. These methods should not perform the operation immediately. Instead, they should create a `ListenableFuture`, queue the operation internally with a future completion tick, and return the future.
* **Prompt 18:** In the `SimulatedStorage` class, implement the `tick` method. This method must act as an event loop, checking the pending operation queue and completing the `ListenableFuture`s for any operations that are now due. Use the seeded `Random` instance to simulate I/O failures.

---

### **Phase 5: The `ListenableFuture` Implementation**

* **Prompt 19:** Generate a generic Java class named `ListenableFuture<T>`. It must be safe for a single-threaded event loop (no blocking calls or external thread pools). It should support `PENDING`, `SUCCEEDED`, and `FAILED` states, have internal `complete(T result)` and `fail(Throwable error)` methods, and provide a non-blocking `onSuccess(Consumer<T> callback)` method.

---

### **Phase 6: Replica Quorum Logic**

* **Prompt 20:** Now, modify the `Replica` class from Phase 1. Add fields for its state: a reference to a `Storage` implementation and a `Map` for tracking pending quorum requests (`Map<RequestId, QuorumState>`). Also define a private helper class, `QuorumState`, to track the progress of a single quorum operation.
* **Prompt 21:** In the `Replica` class, implement the `onMessageReceived(Message message)` method. It should act as a router, using a `switch` statement on the `message.getType()` to delegate to different private handler methods.
* **Prompt 22:** In the `Replica` class, implement the private handler method for `CLIENT_REQUEST`. This method acts as a coordinator: it should create a `QuorumState` object, get a timestamp, and send a new `INTERNAL_*_REQUEST` to all peers and itself.
* **Prompt 23:** In the `Replica` class, implement the private handler method for `INTERNAL_*_REQUEST`. This method acts as a participant: it should call `storage.get()` or `storage.set()`, and then attach a callback to the returned `ListenableFuture` which will send the `INTERNAL_*_RESPONSE` back to the coordinator.
* **Prompt 24:** In the `Replica` class, implement the private handler method for `INTERNAL_*_RESPONSE`. This method acts as the coordinator: it should update the `QuorumState`. If a quorum is reached, it must determine the final result and send the `CLIENT_RESPONSE`.
* **Prompt 25:** In the `Replica` class, implement a `tick(long currentTick)` method for proactive work like sending heartbeats and timing out pending quorum requests.

---

### **Phase 7: The Client**

* **Prompt 26:** Generate a Java class named `Client`. It should have a `Map` to store pending requests (`Map<CorrelationId, ListenableFuture>`). Its constructor should accept a `MessageBus` reference.
* **Prompt 27:** In the `Client` class, implement a `sendRequest(byte[] key, byte[] value)` method that generates a `CorrelationId`, creates and stores a `ListenableFuture`, sends the `CLIENT_REQUEST` message, and returns the future.
* **Prompt 28:** In the `Client` class, implement the `onMessageReceived(Message message)` callback to handle `CLIENT_RESPONSE` messages, find the correct pending `ListenableFuture` using the `CorrelationId`, and complete it.
* **Prompt 29:** In the `Client` class, implement a `tick(long currentTick)` method to check for and handle request timeouts by completing the future exceptionally.

---

### **Phase 8: The Simulation Driver**

* **Prompt 30:** Generate a `SimulationDriver` class with a `main` method.
* **Prompt 31:** Inside `main`, implement the setup logic: instantiate a seeded `Random`, a `SimulatedNetwork`, a `SimulatedStorage`, a `MessageBus`, and all `Replica` and `Client` instances. Inject all dependencies correctly.
* **Prompt 32:** Inside `main`, implement the simulation loop. The loop must iterate for a set number of ticks and, within each iteration, call the `tick()` methods in the correct, critical order: first on all `Clients` and `Replicas`, and second on the `SimulatedStorage` and `SimulatedNetwork`.