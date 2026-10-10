#!/bin/sh
set -e

# Le contrat gRPC est monté au runtime par docker-compose (./core -> /app/core).
# On regénère donc les stubs Python au démarrage, pour qu'ils restent
# toujours synchronisés avec genesis.proto.
echo "Génération des stubs gRPC depuis /app/core/genesis.proto..."
python -m grpc_tools.protoc \
    -I/app/core \
    --python_out=/app/logic \
    --grpc_python_out=/app/logic \
    /app/core/genesis.proto

exec python engine.py
