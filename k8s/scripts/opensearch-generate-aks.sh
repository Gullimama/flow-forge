#!/usr/bin/env bash
# Generate OpenSearch manifests with Service port 80 for AKS (run from repo root).
# Usage: ./k8s/scripts/opensearch-generate-aks.sh
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/k8s/generated/opensearch"
VALUES="$REPO_ROOT/k8s/infrastructure/opensearch/values.yaml"
mkdir -p "$OUT_DIR"
helm template test opensearch/opensearch -f "$VALUES" -n flowforge-infra \
  | awk '/^[[:space:]]*port: 9200/ && !/targetPort/{b=substr($0,1,index($0,"port:")-1); print b"port: 80"; print b"targetPort: 9200"; next} /^[[:space:]]*port: 9300/ && !/targetPort/{b=substr($0,1,index($0,"port:")-1); print b"port: 80"; print b"targetPort: 9300"; next} /^[[:space:]]*port: 9600/ && !/targetPort/{b=substr($0,1,index($0,"port:")-1); print b"port: 80"; print b"targetPort: 9600"; next} {print}' \
  > "$OUT_DIR/opensearch.yaml"
echo "Generated $OUT_DIR/opensearch.yaml"
