#!/bin/bash

# ReplicaTicks Cluster script for the distributed key-value store
# This script demonstrates a 3-node cluster with client operations

set -e  # Exit on any error

# Configuration
REPLICA1_PORT=9001
REPLICA2_PORT=9002
REPLICA3_PORT=9003
DATA_DIR="build/demo-data"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Cleanup function
cleanup() {
    log_info "Cleaning up processes..."
    
    # Send SIGTERM first for graceful shutdown
    pkill -f "ServerApplication" || true
    pkill -f "replicaticks-server-all.jar" || true
    
    # Wait a moment for graceful shutdown
    sleep 2
    
    # Force kill if still running
    pkill -9 -f "ServerApplication" || true
    pkill -9 -f "replicaticks-server-all.jar" || true
    
    log_info "Data directory preserved at: $DATA_DIR"
    log_info "You can inspect the RocksDB files to verify data replication"
    log_success "Process cleanup completed"
}

# Set up cleanup on script exit
trap cleanup EXIT

# Create data directory
mkdir -p "$DATA_DIR"

log_info "Starting 3-node ReplicaTicks distributed key-value store cluster..."

# Start replica 1
log_info "Starting replica 1 on port $REPLICA1_PORT..."
java -jar build/libs/replicaticks-server-all.jar \
    --index=1 \
    --ip=127.0.0.1 \
    --port=$REPLICA1_PORT \
    --peers=127.0.0.1:$REPLICA2_PORT,127.0.0.1:$REPLICA3_PORT \
    --storage=$DATA_DIR/replica1 &
REPLICA1_PID=$!

# Start replica 2
log_info "Starting replica 2 on port $REPLICA2_PORT..."
java -jar build/libs/replicaticks-server-all.jar \
    --index=2 \
    --ip=127.0.0.1 \
    --port=$REPLICA2_PORT \
    --peers=127.0.0.1:$REPLICA1_PORT,127.0.0.1:$REPLICA3_PORT \
    --storage=$DATA_DIR/replica2 &
REPLICA2_PID=$!

# Start replica 3
log_info "Starting replica 3 on port $REPLICA3_PORT..."
java -jar build/libs/replicaticks-server-all.jar \
    --index=3 \
    --ip=127.0.0.1 \
    --port=$REPLICA3_PORT \
    --peers=127.0.0.1:$REPLICA1_PORT,127.0.0.1:$REPLICA2_PORT \
    --storage=$DATA_DIR/replica3 &
REPLICA3_PID=$!

# Wait for replicas to start
log_info "Waiting for replicas to start..."
sleep 5

# Check if replicas are running
if ! kill -0 $REPLICA1_PID 2>/dev/null; then
    log_error "Replica 1 failed to start"
    exit 1
fi

if ! kill -0 $REPLICA2_PID 2>/dev/null; then
    log_error "Replica 2 failed to start"
    exit 1
fi

if ! kill -0 $REPLICA3_PID 2>/dev/null; then
    log_error "Replica 3 failed to start"
    exit 1
fi

log_success "All replicas started successfully!"

# Demo operations
log_info "Running cluster operations..."

# Set a value
log_info "Setting key 'demo-key' to value 'Hello, Distributed World!'"
java -jar build/libs/replicaticks-client-all.jar set 127.0.0.1:9001 demo-key "Hello, Distributed World!"

# Get the value
log_info "Getting value for key 'demo-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:9001 demo-key

# Set another value
log_info "Setting key 'test-key' to value 'Quorum consensus working!'"
java -jar build/libs/replicaticks-client-all.jar set 127.0.0.1:9002 test-key "Quorum consensus working!"

# Get the second value
log_info "Getting value for key 'test-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:9002 test-key

log_success "Cluster operations completed!"

# Test individual servers for data replication
log_info "Testing individual servers for data replication..."

# Test each server for the first key
log_info "=== Testing 'demo-key' on all servers ==="
log_info "Testing Replica 1 (127.0.0.1:$REPLICA1_PORT) for key 'demo-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:$REPLICA1_PORT demo-key

log_info "Testing Replica 2 (127.0.0.1:$REPLICA2_PORT) for key 'demo-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:$REPLICA2_PORT demo-key

log_info "Testing Replica 3 (127.0.0.1:$REPLICA3_PORT) for key 'demo-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:$REPLICA3_PORT demo-key

# Test each server for the second key
log_info "=== Testing 'test-key' on all servers ==="
log_info "Testing Replica 1 (127.0.0.1:$REPLICA1_PORT) for key 'test-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:$REPLICA1_PORT test-key

log_info "Testing Replica 2 (127.0.0.1:$REPLICA2_PORT) for key 'test-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:$REPLICA2_PORT test-key

log_info "Testing Replica 3 (127.0.0.1:$REPLICA3_PORT) for key 'test-key'"
java -jar build/libs/replicaticks-client-all.jar get 127.0.0.1:$REPLICA3_PORT test-key

log_success "Individual server testing completed!"

# Keep running for a bit to show the system is working
log_info "Running cluster for 10 seconds to show system stability..."
sleep 10

log_success "Cluster test completed successfully!"

# Script will auto-cleanup via trap on exit 