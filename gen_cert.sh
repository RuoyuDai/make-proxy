#!/bin/bash
# Generate a self-signed certificate for the TLS tunnel between
# client and server. Run once before starting the server.
set -e
cd "$(dirname "$0")"
mkdir -p priv
openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout priv/server.key -out priv/server.crt \
    -days 3650 -subj "/CN=make-proxy"
chmod 600 priv/server.key
echo "Generated priv/server.crt and priv/server.key"
