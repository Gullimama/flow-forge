# FlowForge Infrastructure Deployment Guide

This guide explains **how to bring up all infrastructure components in the right order**, both for **local development (Docker)** and for a **clustered environment with ArgoCD/Argo Workflows**. It is based on the current codebase (`services/api`, `services/orchestrator`, `libs/*`) and the manifests in `k8s/**`.

The goals:
- Stand up the **minimum stack** needed to run the API and in‑process research pipeline locally.
- Stand up the **full AKS/cluster stack** in an order that respects dependencies (storage → data stores → ML/LLM → API → pipeline/orchestrator → observability).

---

## 1. Dependency model

From `services/api/src/main/resources/application.yml` and the `libs/*` modules, FlowForge depends on:

- **PostgreSQL** — operational metadata, jobs, research runs, snapshot history
- **MinIO** — raw Git repos, parsed artifacts, embeddings, final research output (`output/system-flows-research/...`)
- **OpenSearch** — code/log indices, anomaly/indexed artifacts, topology
- **Neo4j** — knowledge graph (nodes/edges for services, calls, flows)
- **Qdrant** — vector store (code/log embeddings)
- **Redis** — Dapr state/pubsub (cluster only; not used by the API directly)
- **MLflow** — experiment tracking for GNN / classifier / evaluation (optional)
- **LLM backends**:
  - **vLLM** (OpenAI-compatible HTTP API), or
  - **Ollama** (local / cluster Ollama server)
- **TEI (Text Embedding Inference)** — code/log embeddings and reranker (optional but recommended)
- **Argo Workflows** — pipeline orchestration on Kubernetes
- **ArgoCD** — GitOps for all the above (already assumed installed in `argocd` namespace)

The **logical dependency order** is:

1. **Namespaces & ArgoCD project** (target namespaces must exist first)
2. **Core stateful services**: Postgres, MinIO
3. **Search/graph/vector stores**: OpenSearch, Neo4j, Qdrant
4. **Dapr + Redis** (if running Dapr-based flows in cluster)
5. **LLM/ML services**: vLLM, Ollama, TEI, MLflow
6. **Argo Workflows** (needs storage backend in MinIO for artifacts)
7. **FlowForge API & orchestrator** (needs all backing services reachable)
8. **Observability stack**: Prometheus/Grafana, Tempo, dashboards


---

## 2. Local development (Docker)

For local work, the recommended path is **Docker Compose + local Spring profile (`local`)**. This path does **not** use Kubernetes or Argo; it is sufficient to:

- Start the API on port **9080** (with `spring.profiles.active=local`)
- Trigger snapshot ingest against a local Git repo (`file://...`)
- Trigger a research run (in‑process dispatcher)
- Fetch `system-flows-research.md` via the API

### 2.1 Bring up local infra

The repo already includes a local docker-compose at `docker/docker-compose.yml`:

```yaml
# LOCAL DEV ONLY — not used on AKS
services:
  postgres:
    image: postgres:16-alpine
    ports:
      - "55432:5432"
    environment:
      POSTGRES_DB: flowforge
      POSTGRES_USER: flowforge
      POSTGRES_PASSWORD: flowforge
    volumes:
      - postgres-data:/var/lib/postgresql/data

  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    command: server /data --console-address ":9001"
    volumes:
      - minio-data:/data

  opensearch:
    image: opensearchproject/opensearch:2.18.0
    ports:
      - "9200:9200"
    environment:
      discovery.type: single-node
      plugins.security.disabled: "true"
      OPENSEARCH_INITIAL_ADMIN_PASSWORD: admin
    volumes:
      - opensearch-data:/usr/share/opensearch/data

  neo4j:
    image: neo4j:5-community
    ports:
      - "7474:7474"
      - "7687:7687"
    environment:
      NEO4J_AUTH: neo4j/password
      NEO4J_server_security_auth__minimum__role__for__authentication: NONE
    volumes:
      - neo4j-data:/data

  qdrant:
    image: qdrant/qdrant:v1.12.1
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - qdrant-data:/qdrant/storage

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  postgres-data:
  minio-data:
  opensearch-data:
  neo4j-data:
  qdrant-data:
```

**Order (Docker):**

1. Ensure Docker Desktop is running.
2. From the repo root:

   ```bash
   cd docker
   docker compose up -d
   ```

3. Verify core endpoints:

   - Postgres: `localhost:55432`
   - MinIO: `http://localhost:9000` (console at `http://localhost:9001`)
   - OpenSearch: `http://localhost:9200`
   - Neo4j: `http://localhost:7474` (user `neo4j`, password `password`)
   - Qdrant: `http://localhost:6333`
   - Redis: `localhost:6379`

At this point **all stores the API needs are up**, but **they do not yet contain any data** (no indices, graph, vectors). For in‑process research runs, that’s acceptable if you only want to validate plumbing; for meaningful research output, you eventually need the parsing/graph/embedding pipeline as well.

### 2.2 Start the API (local profile)

The `local` profile for the API is defined in `services/api/src/main/resources/application-local.yml`:

```yaml
server:
  port: ${FLOWFORGE_API_PORT:9080}

spring:
  datasource:
    url: ${FLOWFORGE_POSTGRES_URL:jdbc:postgresql://localhost:55432/flowforge}
  flyway:
    enabled: true

flowforge:
  dispatch:
    mode: ${FLOWFORGE_DISPATCH_MODE:in-process}
  ollama:
    base-url: ${FLOWFORGE_OLLAMA_BASE_URL:http://localhost:11434}
    chat-model: ${FLOWFORGE_OLLAMA_CHAT_MODEL:llama3.1}
```

**Key points:**

- API listens on **9080** by default (overridable with `FLOWFORGE_API_PORT`).
- Uses **Postgres on 55432** (from Docker Compose).
- Dispatch mode is **in-process** so snapshot and research jobs run inside the API.
- If `FLOWFORGE_OLLAMA_BASE_URL` is set, the ChatModel uses **Ollama**; otherwise it falls back to vLLM (OpenAI-compatible), which you would run separately.

**Start the API:**

```bash
./gradlew :services:api:bootRun --args='--spring.profiles.active=local'
```

**Check health:**

```bash
curl -s http://localhost:9080/actuator/health/liveness
# => {"status":"UP"}
```

### 2.3 Minimal local LLM and research

For a **local Ollama** setup (optional, but avoids vLLM):

1. Install and run Ollama on your machine (outside Docker), then:

   ```bash
   ollama pull llama3.1
   ollama serve   # or use the Ollama app
   ```

2. Ensure it’s reachable:

   ```bash
   curl -s http://localhost:11434/api/tags
   ```

3. The `local` profile defaults to `base-url: http://localhost:11434` and `chat-model: llama3.1`, so no further config is needed.

**Embedding provider (TEI vs Ollama):**

- Embeddings (code and log) are provided by either **TEI** (Text Embedding Inference) or **Ollama**, controlled by `flowforge.embedding.provider` (`tei` | `ollama`).
- **Local (macOS):** The `local` profile defaults to `provider: ollama` so you do **not** need TEI. Official TEI Docker images are amd64-only and do not run on arm64 (e.g. Apple Silicon). Run Ollama and pull an embedding model, e.g. `ollama pull nomic-embed-text`. The API uses `flowforge.ollama.base-url` and `flowforge.ollama.embedding-model` (default `nomic-embed-text`).
- **AKS/cluster:** Use `provider: tei` (default in the main profile) and deploy the TEI code/log/reranker services from `k8s/ml-serving/tei-*` so the API uses `flowforge.tei.code-url`, `flowforge.tei.log-url`, and `flowforge.tei.reranker-url`.
- Override with `FLOWFORGE_EMBEDDING_PROVIDER=tei` or `ollama` as needed.

Once API + infra + LLM are up, you can:

1. Trigger a **baseline snapshot** against the FlowForge repo itself:

   ```bash
   REPO_PATH="/Users/you/projects/tesco/flow-forge"  # adjust for your machine
   curl -s -X POST http://localhost:9080/api/v1/snapshots/master \
     -H "Content-Type: application/json" \
     -d "{\"repoUrl\": \"file://${REPO_PATH}\", \"githubToken\": \"\"}"
   ```

2. Watch job status (replace `<jobId>`):

   ```bash
   curl -s http://localhost:9080/api/v1/jobs/<jobId>
   ```

3. Start a **research run** for the latest snapshot (see `docs/FLOWFORGE-APPLICATION-FLOW.md` for the exact flow). After completion, download the report:

   ```bash
   SNAPSHOT_ID=<snapshot-uuid>
   curl -o system-flows-research.md \
     "http://localhost:9080/api/v1/research/output/${SNAPSHOT_ID}"
   ```

This covers **local, non-K8s** usage.

---

## 3. Cluster / AKS deployment with ArgoCD

For a full cluster deployment, you use **ArgoCD** (already present in `argocd` namespace) and the GitOps setup under `k8s/argocd/**`.

### 3.1 High-level order (cluster)

ArgoCD already encodes a sync order using `argocd.argoproj.io/sync-wave` annotations and Application dependencies. The effective order is:

1. **Namespaces** (`k8s/argocd/apps/namespaces.yaml`)
2. **Core infra** (`flowforge-infra` namespace): Postgres, MinIO
3. **Search/graph/vector** (`flowforge-infra`): OpenSearch, Neo4j, Qdrant
4. **Dapr + Redis**: Dapr system components + Redis in `flowforge-infra`
5. **ML/LLM** (`flowforge-ml`): TEI (code/log/reranker), vLLM, Ollama
6. **Argo Workflows** (`argo` namespace)
7. **FlowForge API** (`flowforge` namespace)
8. **Observability** (`flowforge-obs`): kube-prometheus-stack, Tempo, dashboards

The **App-of-apps** (`k8s/argocd/app-of-apps.yaml`) points ArgoCD at `k8s/argocd/apps` and lets the sync waves orchestrate the order.

### 3.2 One-time ArgoCD wiring

Prereq: ArgoCD installed in `argocd` namespace.

1. **Create the ArgoCD project** (once):

   ```bash
   kubectl apply -f k8s/argocd/project.yaml
   ```

2. **Create the root Application** (app-of-apps):

   ```bash
   kubectl apply -f k8s/argocd/app-of-apps.yaml
   ```

   This tells ArgoCD to manage everything under `k8s/argocd/apps` from the Git repo.

3. **(If your cluster is air‑gapped or GitHub requires auth)** configure ArgoCD repo access (outside the scope of this file; see your platform docs). Once the repo is reachable, ArgoCD will sync everything below in the right order.

### 3.3 Namespaces

`k8s/argocd/apps/namespaces.yaml` defines:

- `flowforge`
- `flowforge-infra`
- `flowforge-ml`
- `flowforge-obs`

These are annotated with `sync-wave: "0"` so they are created **before** any other apps that target them.

You don’t apply this file directly; ArgoCD does it via the root app.

### 3.4 Core infra: Postgres & MinIO

Postgres and MinIO live in **`flowforge-infra`** and share sync wave `"1"`:

- `k8s/argocd/apps/postgresql.yaml`
- `k8s/argocd/apps/minio.yaml`

Both are Bitnami Helm charts, configured via values files under `k8s/infrastructure/postgresql` and `k8s/infrastructure/minio`.

They must be up before:

- The API can start (it needs Postgres).
- Argo Workflows can store artifacts (it needs MinIO; see `k8s/infrastructure/argo-workflows/values.yaml`).

### 3.5 Search/graph/vector: OpenSearch, Neo4j, Qdrant

These run in **`flowforge-infra`** and have sync wave `"2"`:

- `k8s/argocd/apps/opensearch.yaml`
- `k8s/argocd/apps/neo4j.yaml`
- `k8s/argocd/apps/qdrant.yaml`

The API uses them via `flowforge.opensearch`, `flowforge.neo4j`, `flowforge.qdrant` properties. They must be up before running the **pipeline stages** that index/search or build the graph, and before running **research** that depends on retrieval and graph queries.

### 3.6 Dapr + Redis

For Dapr-based workflows, `k8s/dapr/*.yaml` plus a Redis instance (via infra) are used. The ArgoCD app for Dapr is:

- `k8s/argocd/apps/dapr.yaml`

This should sync **after** `flowforge-infra` exists, since Dapr components may reference services in that namespace. Dapr is **not required** for the API or in-process research, but is necessary for the full production pipeline.

### 3.7 ML/LLM services: TEI, vLLM, Ollama

In **`flowforge-ml`** namespace, these apps provide ML functionality:

- `k8s/argocd/apps/tei-code.yaml`
- `k8s/argocd/apps/tei-log.yaml`
- `k8s/argocd/apps/tei-reranker.yaml`
- `k8s/argocd/apps/vllm.yaml`
- `k8s/argocd/apps/ollama.yaml`

They expose services like:

- `tei-code.flowforge-ml.svc.cluster.local`
- `tei-log.flowforge-ml.svc.cluster.local`
- `tei-reranker.flowforge-ml.svc.cluster.local`
- `vllm.flowforge-ml.svc.cluster.local`
- `ollama.flowforge-ml.svc.cluster.local`

The API references these via `flowforge.tei.*` and `flowforge.vllm.*` (and, for Ollama, `flowforge.ollama.*` in the cluster profile).

You should let ArgoCD sync these **before** running any pipeline stages that need embeddings, reranking, or LLM calls.

### 3.8 Argo Workflows

Argo Workflows is installed via:

- `k8s/argocd/apps/argo-workflows.yaml`
- Helm chart values: `k8s/infrastructure/argo-workflows/values.yaml`

This creates the **`argo`** namespace and the Argo Workflows controller/server, using MinIO for artifact storage:

```yaml
artifactRepository:
  s3:
    bucket: argo-artifacts
    endpoint: flowforge-minio.flowforge-infra.svc.cluster.local:9000
    insecure: true
```

Argo Workflows should be up **after**:

- MinIO (for artifact storage).
- Namespaces (`argo` is created by the chart itself, but the helm release runs under ArgoCD in `argocd`).

### 3.9 FlowForge API (cluster)

The API is deployed via:

- `k8s/argocd/apps/flowforge-api.yaml`
- Manifests under `k8s/app/flowforge-api` (Deployment, Service, Ingress, HPA)

It targets the `flowforge` namespace and expects all backing services to be reachable via cluster DNS:

- Postgres in `flowforge-infra`
- MinIO in `flowforge-infra`
- OpenSearch, Neo4j, Qdrant in `flowforge-infra`
- TEI/vLLM/Ollama in `flowforge-ml`

The **cluster profile** (e.g. `aks`) sets those URLs appropriately (see development guides).

**Order:** ArgoCD sync wave for `flowforge-api` is `"7"`, ensuring it comes **after** infra and ML/LLM services.

### 3.10 Observability stack

In `flowforge-obs` there are apps for:

- `k8s/argocd/apps/kube-prometheus-stack.yaml`
- `k8s/argocd/apps/tempo.yaml`

These install Prometheus, Grafana, Tempo, as configured by:

- `k8s/observability/kube-prometheus-stack/values.yaml`
- `k8s/observability/tempo/values.yaml`
- `k8s/grafana/flowforge-pipeline-dashboard.json`

You can treat these as **last** in the infra chain; they scrape metrics and traces from already-running services.

---

## 4. Putting it together: recommended sequences

### 4.1 Local (Docker + API)

1. `docker compose up -d` from `docker/`.
2. `./gradlew :services:api:bootRun --args='--spring.profiles.active=local'`.
3. (Optional) Run Ollama or vLLM locally.
4. Use API to:
   - create baseline snapshot (`POST /api/v1/snapshots/master`),
   - start research run (`POST /api/v1/research/run`),
   - download `system-flows-research.md` (`GET /api/v1/research/output/{snapshotId}`).

### 4.2 Cluster (ArgoCD-driven)

1. Ensure ArgoCD is installed in `argocd` namespace.
2. `kubectl apply -f k8s/argocd/project.yaml`.
3. `kubectl apply -f k8s/argocd/app-of-apps.yaml`.
4. Fix ArgoCD repo credentials/egress so it can clone `https://github.com/Gullimama/flow-forge.git`.
5. Let ArgoCD **auto-sync** everything under `k8s/argocd/apps`:
   - Namespaces
   - Postgres + MinIO
   - OpenSearch, Neo4j, Qdrant
   - Dapr + Redis
   - TEI, vLLM, Ollama
   - Argo Workflows
   - FlowForge API
   - Observability
6. Verify:
   - `kubectl get ns` shows `flowforge`, `flowforge-infra`, `flowforge-ml`, `flowforge-obs`, `argo`.
   - `kubectl -n flowforge-infra get pods` shows Postgres, MinIO, OpenSearch, Neo4j, Qdrant.
   - `kubectl -n flowforge-ml get pods` shows TEI, vLLM, Ollama.
   - `kubectl -n argo get pods` shows Argo Workflows pods.
   - `kubectl -n flowforge get pods` shows FlowForge API.

Once this is in place, you can:

- Use `services/orchestrator` (or direct `argo` CLI) to run the full Argo pipeline (`k8s/argo/flowforge-pipeline.yaml`), which executes all stages (clone → parse → index → embed → graph → candidates → synthesis → publish).
- Use the API for snapshot management, research runs, and retrieving the final `system-flows-research.md` stored in MinIO under `output/system-flows-research/{snapshotId}/system-flows-research.md`.

---

## 5. Design rationale

- **Postgres & MinIO first**: everything else either persists metadata to Postgres or stores artifacts to MinIO.
- **Search/graph/vector next**: code/log parsers, graph builder, and retrieval all depend on OpenSearch, Neo4j, Qdrant being reachable.
- **LLM/TEI before pipeline/Research**: synthesis and embeddings should not start until TEI and LLM endpoints are live.
- **Argo Workflows after storage**: it uses MinIO for artifacts and Kubernetes for pods; it must be able to reference both.
- **API last**: the API should see all backing services as healthy so `/actuator/health` is a realistic readiness signal.
- **Observability last**: metrics/traces/ dashboards depend on the workloads being present; they can be added at any time but are most useful once everything else is up.

This ordering gives you a **clean, dependency-respecting rollout**, whether you run locally (Docker + Spring profile) or in a cluster (ArgoCD + Helm charts).

