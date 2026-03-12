#!/usr/bin/env bash
# Generate Neo4j manifests with Service port 80 for AKS (run from repo root).
# Usage: ./k8s/scripts/neo4j-generate-aks.sh
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/k8s/generated/neo4j"
VALUES="$REPO_ROOT/k8s/infrastructure/neo4j/values.yaml"
mkdir -p "$OUT_DIR"
helm template neo4j neo4j/neo4j --version 5.25.1 -f "$VALUES" -n flowforge-infra \
  | sed -E 's/^([[:space:]]*)port: 7687/\1port: 80/' \
  > "$OUT_DIR/neo4j.yaml"
echo "Generated $OUT_DIR/neo4j.yaml"
