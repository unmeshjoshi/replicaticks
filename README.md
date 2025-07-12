# Deterministic Distributed Key-Value Store

A production-ready, deterministic simulation of a distributed key-value store with quorum-based consensus, featuring TigerBeetle-style failure injection and comprehensive testing capabilities.

##  Project Overview

This project implements a **deterministic simulation** of a distributed key-value store that can also run in production environments. The system simulates network failures, storage delays, and other real-world conditions in a completely deterministic and reproducible manner, while also supporting real network deployment.

###  Key Features

- **Quorum Support**: Majority-based consensus ensuring data consistency across replicas
- **Dual Network Support**: Both simulated (deterministic) and real NIO-based networking
- **Pluggable Storage**: Simulated storage for testing, RocksDB for production
- **TigerBeetle-Style Testing**: Continuous stress testing with failure injection
- **Real-Time Monitoring**: Live performance metrics and statistics
- **Production Ready**: Fat JARs, CLI tools, cluster orchestration

## Architecture

### Core Design Principles

1. **Single-Threaded Event Loop**: Eliminates locks and race conditions through sequential execution
2. **Deterministic Simulation**: Seeded random generators ensure reproducible behavior
3. **Asynchronous I/O**: Non-blocking operations using custom `ListenableFuture`
4. **Clean Architecture**: Separated concerns with abstract base classes for extensibility

### System Components

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│     Client      │    │     Client      │    │     Client      │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 │
    ┌────────────────────────────┼────────────────────────────┐
    │                            │                            │
┌───▼────┐                  ┌────▼────┐                  ┌────▼────┐
│Replica1│◄────────────────►│Replica2 │◄────────────────►│Replica3 │
│ :8000  │                  │ :8001   │                  │ :8002   │
└────────┘                  └─────────┘                  └─────────┘
```

### Layer Architecture

1. **Application Layer**: `Client`, `QuorumBasedReplica`
2. **Messaging Layer**: `ClientMessageBus`, `ServerMessageBus`
3. **Network Layer**: `SimulatedNetwork`, `NioNetwork`
4. **Storage Layer**: `SimulatedStorage`, `RocksDbStorage`
5. **Foundation Layer**: `Message`, `NetworkAddress`, `MessageCodec`

##  Quick Start

### Building the Project

```bash
# Build fat JARs for deployment
./gradlew shadowJar

# Run all tests
./gradlew test

# Clean build
./gradlew clean build
```

### Running a 3-Node Cluster Demo

The easiest way to see the system in action:

```bash
# Run the automated 3-node cluster demo
./scripts/run-demo.sh
```

This script will:
1. Start 3 replica nodes (ports 8000, 8001, 8002)
2. Perform SET/GET operations across the cluster
3. Demonstrate quorum consensus
4. Clean shutdown of all nodes

### Manual Cluster Setup

#### Start Server Nodes

```bash
# Terminal 1 - Start Replica 1
java -jar build/libs/replicated-server.jar \
  --port 8000 \
  --peers 127.0.0.1:8001,127.0.0.1:8002 \
  --data-dir /tmp/replica1

# Terminal 2 - Start Replica 2  
java -jar build/libs/replicated-server.jar \
  --port 8001 \
  --peers 127.0.0.1:8000,127.0.0.1:8002 \
  --data-dir /tmp/replica2

# Terminal 3 - Start Replica 3
java -jar build/libs/replicated-server.jar \
  --port 8002 \
  --peers 127.0.0.1:8000,127.0.0.1:8001 \
  --data-dir /tmp/replica3
```

#### Client Operations

```bash
# Set a value
java -jar build/libs/replicated-client.jar \
  --operation set \
  --key "user:123" \
  --value "John Doe" \
  --replicas 127.0.0.1:8000,127.0.0.1:8001,127.0.0.1:8002

# Get a value
java -jar build/libs/replicated-client.jar \
  --operation get \
  --key "user:123" \
  --replicas 127.0.0.1:8000,127.0.0.1:8001,127.0.0.1:8002
```

## 🧪 TigerBeetle-Style Simulation Testing

### Running Stress Tests

The system includes a comprehensive SimulationRunner for continuous stress testing with failure injection:

```bash
# 5-minute stress test with aggressive failures
java -cp build/libs/replicated-server.jar replicated.simulation.SimulationRunner \
  --duration 300 \
  --failure-mode aggressive \
  --nodes 3

# 1-minute mild failure testing
java -cp build/libs/replicated-server.jar replicated.simulation.SimulationRunner \
  --duration 60 \
  --failure-mode mild \
  --nodes 3

# Chaos testing mode
java -cp build/libs/replicated-server.jar replicated.simulation.SimulationRunner \
  --duration 120 \
  --failure-mode chaos \
  --nodes 5
```

### Simulation Features

- **Continuous Workload**: Random GET/SET operations
- **Network Partitions**: Dynamic partition creation and healing
- **Packet Loss**: Configurable packet loss simulation
- **Storage Failures**: Simulated storage failures and recovery
- **Real-Time Monitoring**: Live statistics and performance metrics

### Example Output

```
Simulation completed!
=== FINAL SIMULATION STATISTICS ===
Duration: 300061ms (300s)
Total Operations: 65491 total, 64637 success (98.7%), 541 failed, 313 timeout
Network Failures: 50, Storage Failures: 0
Partitions: 0 active

Performance: 218 ops/sec sustained throughput
Replica Health: All 3 replicas operational
```

## 🔧 Development & Testing

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test categories
./gradlew test --tests "replicated.integration.*"
./gradlew test --tests "replicated.network.*" 
./gradlew test --tests "replicated.replica.*"

# Continuous testing
./gradlew test --continuous
```

### Adding New Replication Algorithms

The architecture supports multiple replication algorithms through abstract base classes:

```java
// Extend the base Replica class
public class RaftReplica extends Replica {
    // Implement Raft-specific consensus logic
}

// Or implement a different approach
public class ChainReplicationReplica extends Replica {
    // Implement chain replication logic
}
```

##️ Configuration Options

### Server Configuration

| Option | Description | Default |
|--------|-------------|---------|
| `--port` | Server listening port | Required |
| `--peers` | Comma-separated list of peer addresses | Required |
| `--data-dir` | Directory for persistent data | `./data` |
| `--network-type` | `nio` or `simulated` | `nio` |
| `--storage-type` | `rocksdb` or `simulated` | `rocksdb` |

### Client Configuration

| Option | Description | Default |
|--------|-------------|---------|
| `--operation` | `get` or `set` | Required |
| `--key` | Key for operation | Required |
| `--value` | Value for SET operations | Required for SET |
| `--replicas` | Comma-separated replica addresses | Required |
| `--timeout` | Request timeout in milliseconds | `5000` |

### Simulation Configuration

| Option | Description | Default |
|--------|-------------|---------|
| `--duration` | Simulation duration in seconds | Required |
| `--failure-mode` | `mild`, `aggressive`, or `chaos` | `mild` |
| `--nodes` | Number of replica nodes | `3` |
| `--operations-per-second` | Target operation rate | `100` |

### Code Standards

- Follow **Tidy First** principles: separate structural from behavioral changes
- Maintain **single responsibility** principle
- Use **intention-revealing names** for methods and variables
- Keep methods **small and focused** (< 50 lines preferred)
- Ensure **deterministic behavior** in all components

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- **TigerBeetle**: Inspiration for deterministic simulation and testing approaches
- **Kent Beck**: For the tidy first system prompt that I used in the cursorrules

## 📚 Further Reading

- [Deterministic Simulation in Distributed Systems](plan.md)
- [Project Plan](plan.md)

--- 

## Using as a Library

### Dependency coordinates (Maven Local)

Until the project is published to a remote repository you can depend on the locally-installed artifact:

```gradle
repositories {
    mavenLocal()
}

dependencies {
    implementation 'io.github.unmeshjoshi:replicaticks:0.1.0-alpha.1'
}
```

### Extending `MessageType`

`MessageType` is an extensible constant class (similar to Netty’s `HttpMethod`).  You can declare your own message types in client code like so:

```java
public final class RaftMessageTypes {
    public static final MessageType RAFT_APPEND_ENTRIES = MessageType.valueOf(
            "RAFT_APPEND_ENTRIES",
            MessageTypeInterface.Category.INTERNAL_REQUEST,
            5_000 // timeout in ms
    );
}
```

Registering multiple custom types is safe; the global registry guarantees singleton instances, so you can compare them with `==` just like traditional enums. 