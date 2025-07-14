# Manual Testing Guide for Individual Servers

## Testing Individual Server Data Replication

After running the cluster test, you can manually verify that each server has the stored data.

### Prerequisites
1. Run the cluster test first: `./gradlew runCluster`
2. Keep the cluster running (don't stop it)

### Method 1: Direct Client Commands

Test each server individually for the stored keys:

```bash
# Test demo-key on all servers
echo "=== Testing demo-key ==="
java -jar build/libs/replicated-client-all.jar get 127.0.0.1:9001 demo-key
java -jar build/libs/replicated-client-all.jar get 127.0.0.1:9002 demo-key
java -jar build/libs/replicated-client-all.jar get 127.0.0.1:9003 demo-key

# Test test-key on all servers
echo "=== Testing test-key ==="
java -jar build/libs/replicated-client-all.jar get 127.0.0.1:9001 test-key
java -jar build/libs/replicated-client-all.jar get 127.0.0.1:9002 test-key
java -jar build/libs/replicated-client-all.jar get 127.0.0.1:9003 test-key
```

### Method 2: Using the Test Script

```bash
# Run the individual server test script
./scripts/test-individual-servers.sh
```

### Method 3: Using Gradle Task

```bash
# Run the gradle task for individual testing
./gradlew testIndividualServers
```

### Method 4: Inspect Storage Files

After the cluster stops, you can inspect the RocksDB storage files:

```bash
# List storage directories
ls -la build/demo-data/

# Each replica has its own storage directory:
# - build/demo-data/replica1/
# - build/demo-data/replica2/
# - build/demo-data/replica3/

# Use the gradle task to inspect data
./gradlew inspectData

# You can use RocksDB tools to inspect the data (if available)
# or check file sizes to verify data was written
du -sh build/demo-data/replica*
```

### Expected Results

If data replication is working correctly, you should see:

1. **All servers return the same values** for the same keys
2. **demo-key** should return "Hello, Distributed World!" from all servers
3. **test-key** should return "Quorum consensus working!" from all servers
4. **Storage directories** should contain data files (RocksDB files)

### Troubleshooting

If some servers don't have the data:

1. **Check server logs** for errors during replication
2. **Verify network connectivity** between replicas
3. **Check quorum configuration** - ensure enough replicas are participating
4. **Look for timeout errors** in the client or server logs

### Example Output

Successful testing should show:
```
=== Testing demo-key ===
Getting key: demo-key from server: 127.0.0.1:9001
Value: Hello, Distributed World!
Getting key: demo-key from server: 127.0.0.1:9002
Value: Hello, Distributed World!
Getting key: demo-key from server: 127.0.0.1:9003
Value: Hello, Distributed World!

=== Testing test-key ===
Getting key: test-key from server: 127.0.0.1:9001
Value: Quorum consensus working!
Getting key: test-key from server: 127.0.0.1:9002
Value: Quorum consensus working!
Getting key: test-key from server: 127.0.0.1:9003
Value: Quorum consensus working!
``` 