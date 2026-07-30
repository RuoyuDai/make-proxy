#!/bin/bash
# Stop the make-proxy server.
cd "$(dirname "$0")"
PID=$(pgrep -f 'make_proxy start_server' | head -1)
if [ -n "$PID" ]; then
    echo "stopping make_proxy server (pid $PID)"
    kill "$PID"
else
    echo "make_proxy server is not running"
fi
