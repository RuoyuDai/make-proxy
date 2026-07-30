#!/bin/bash
# Stop the make-proxy client.
cd "$(dirname "$0")"
PID=$(pgrep -f 'make_proxy start_client' | head -1)
if [ -n "$PID" ]; then
    echo "stopping make_proxy client (pid $PID)"
    kill "$PID"
else
    echo "make_proxy client is not running"
fi
