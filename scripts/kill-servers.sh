#!/bin/bash
# Kill all running ReplicaTicks server processes

set -e

PIDS=$(ps aux | grep '[r]eplicaticks-server-all.jar' | awk '{print $2}')

if [ -z "$PIDS" ]; then
  echo "No replicaticks-server-all.jar processes found."
  exit 0
fi

echo "Killing replicaticks-server-all.jar processes: $PIDS"
kill $PIDS

# Wait for processes to exit
sleep 1

# Double-check if any remain
PIDS_LEFT=$(ps aux | grep '[r]eplicaticks-server-all.jar' | awk '{print $2}')
if [ -n "$PIDS_LEFT" ]; then
  echo "Some processes did not exit, killing with -9: $PIDS_LEFT"
  kill -9 $PIDS_LEFT
else
  echo "All replicaticks-server-all.jar processes killed."
fi 