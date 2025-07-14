#!/bin/bash

# Individual server testing script
# This script tests each server individually to verify data replication

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

# Test function for individual server
test_server() {
    local server_name=$1
    local server_address=$2
    local key=$3
    local expected_value=$4
    
    log_info "Testing $server_name ($server_address) for key '$key'"
    
    # Try to get the value from this specific server
    local result
    result=$(java -jar build/libs/replicated-client-all.jar get "$server_address" "$key" 2>&1)
    
    if echo "$result" | grep -q "$expected_value"; then
        log_success "$server_name has the correct value: '$expected_value'"
        return 0
    else
        log_error "$server_name does not have the expected value"
        log_info "Actual result: $result"
        return 1
    fi
}

# Main testing
log_info "Testing individual servers for data replication..."

# Test each server for the first key
log_info "=== Testing 'demo-key' on all servers ==="
test_server "Replica 1" "127.0.0.1:$REPLICA1_PORT" "demo-key" "Hello, Distributed World!"
test_server "Replica 2" "127.0.0.1:$REPLICA2_PORT" "demo-key" "Hello, Distributed World!"
test_server "Replica 3" "127.0.0.1:$REPLICA3_PORT" "demo-key" "Hello, Distributed World!"

# Test each server for the second key
log_info "=== Testing 'test-key' on all servers ==="
test_server "Replica 1" "127.0.0.1:$REPLICA1_PORT" "test-key" "Quorum consensus working!"
test_server "Replica 2" "127.0.0.1:$REPLICA2_PORT" "test-key" "Quorum consensus working!"
test_server "Replica 3" "127.0.0.1:$REPLICA3_PORT" "test-key" "Quorum consensus working!"

log_success "Individual server testing completed!" 