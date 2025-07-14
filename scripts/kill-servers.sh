#!/bin/bash
# Kill all running replicated-server.jar processes

set -e

PIDS=$(ps aux | grep '[r]eplicated-server-all.jar' | awk '{print $2}')

if [ -z "$PIDS" ]; then
  echo "No replicated-server-all.jar processes found."
  exit 0
fi

echo "Killing replicated-server-all.jar processes: $PIDS"
kill $PIDS

# Wait for processes to exit
sleep 1

# Double-check if any remain
PIDS_LEFT=$(ps aux | grep '[r]eplicated-server-all.jar' | awk '{print $2}')
if [ -n "$PIDS_LEFT" ]; then
  echo "Some processes did not exit, killing with -9: $PIDS_LEFT"
  kill -9 $PIDS_LEFT
else
  echo "All replicated-server-all.jar processes killed."
fi 