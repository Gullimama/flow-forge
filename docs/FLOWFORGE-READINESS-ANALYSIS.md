# FlowForge — Application & Infrastructure Readiness Analysis

## Executive summary

| Area | Ready | Partial | Not ready |
|------|-------|---------|-----------|
| **Services** | API, Orchestrator | — | Pipeline (no JAR/image in repo) |
| **Libs (20)** | 14 | 5 | 0 |
| **Infra (Docker)** | Postgres, MinIO, OpenSearch, Neo4j, Qdrant, Redis | — | MLflow, Ollama, vLLM, TEI |
| **Infra (K8s/Argo)** | Postgres, MinIO, OpenSearch, Neo4j, Qdrant, Argo, TEI, vLLM, Ollama | Redis (Dapr ref, no app), MLflow (no app) | — |

**Critical gap:** The Argo workflow expects container images `flowforge-pipeline-cpu:latest` and `flowforge-pipeline-gpu:latest` (running `java -jar /app/pipeline.jar --stage=...`). There is **no `services/pipeline`** module in this repo, so those images are not built here. Parsing, graph build, embedding, and similar stages are intended to run in that pipeline; the **API’s in-process path** only runs snapshot ingest, research (candidates → synthesis → publish), and optional blob ingest. For research to produce a meaningful `system-flows-research.md`, Neo4j/OpenSearch/Qdrant must already be populated (e.g. by a separate run of the pipeline or by pre-loading data).

---

## 1. Application components

### 1.1 Services

| Service | Status | Responsibilities |
|---------|--------|-------------------|
| **API** (`services/api`) | **Ready** | REST API: snapshots (baseline/refresh), log ingest, research run, job status, latest snapshot/research, download of `system-flows-research.md`. With `flowforge.dispatch.mode=in-process`: runs snapshot worker, blob ingest worker (if Azure configured), and research pipeline (flow candidates → synthesis → publish) in-process. |
| **Orchestrator** (`services/orchestrator`) | **Ready** | Submits and monitors Argo Workflows (`POST/GET /api/v1/pipelines`). Uses Fabric8 K8s client. No pipeline JAR in repo. |
| **Pipeline** | **Not ready** | Argo expects `flowforge-pipeline-cpu` / `flowforge-pipeline-gpu` images and `pipeline.jar`. No `services/pipeline` in `settings.gradle.kts`; images not built by this repo. |

### 1.2 Libs (modules)

| Lib | Status | Notes |
|-----|--------|--------|
| **common** | Ready | Config, entities, JPA, MinIO, OpenSearch wrapper, Flyway, index init. |
| **ingest** | Ready | GitHub snapshot (clone, classify, MinIO), Azure blob optional. |
| **code-parser** | Ready | Java/Micronaut parsing → OpenSearch. |
| **log-parser** | Ready | Drain-based log parsing → OpenSearch. |
| **topology** | Partial | `inferEdgesFromCodeIndex()` returns empty list (stub). |
| **graph** | Ready | Neo4j graph build and query. |
| **anomaly** | Ready | Anomaly detection, episodes → OpenSearch. |
| **pattern-mining** | Partial | Some branches return empty lists. |
| **vector-store** | Partial | Uses `StubEmbeddingModel` when TEI beans absent. |
| **embedding** | Ready | Code/log embedding via TEI. |
| **reranker** | Ready | TEI cross-encoder. |
| **retrieval** | Ready | Hybrid (OpenSearch + Qdrant + reranker). |
| **flow-builder** | Partial | HTTP/Kafka flows implemented; error/pattern flows return empty. |
| **llm** | Ready | Ollama or vLLM (OpenAI-compatible). |
| **synthesis** | Ready | 6-stage synthesis pipeline. |
| **publisher** | Ready | FreeMarker → MinIO `output` bucket. |
| **gnn** | Partial | Conditional on model path; returns empty on failure. |
| **classifier** | Partial | Conditional beans (JavaParser, code embedding). |
| **mlflow** | Ready | Client, experiment tracking, health. |
| **evaluation** | Ready | Retrieval/synthesis metrics. |
| **dapr** | Ready | Pipeline event handler. |
| **observability** | Ready | Tracing, span processor. |

---

## 2. Infrastructure dependencies

### 2.1 From configuration

| Dependency | application.yml | application-local.yml | Docker Compose | K8s/ArgoCD |
|------------|-----------------|----------------------|----------------|------------|
| **PostgreSQL** | ✓ (5432 default) | ✓ (55432 default) | ✓ port 55432 | ✓ |
| **MinIO** | ✓ | — | ✓ 9000, 9001 | ✓ |
| **OpenSearch** | ✓ | — | ✓ 9200 | ✓ |
| **Neo4j** | ✓ | — | ✓ 7474, 7687 | ✓ |
| **Qdrant** | ✓ | — | ✓ 6333, 6334 | ✓ |
| **Redis** | — | — | ✓ 6379 | Dapr ref only (no Argo app) |
| **MLflow** | ✓ (trackingUri, etc.) | — | ✗ | ✗ |
| **Ollama** | — | ✓ (base-url, chat-model) | ✗ | ✓ |
| **vLLM** | ✓ | — | ✗ | ✓ |
| **TEI (code/log/reranker)** | ✓ | — | ✗ | ✓ |

### 2.2 Docker Compose (`docker/docker-compose.yml`)

**Present:** postgres (55432), minio, opensearch, neo4j, qdrant, redis.

**Not in Compose:** MLflow, Ollama, vLLM, TEI. For local LLM you run Ollama yourself (or add a service); for full synthesis without Ollama you need vLLM elsewhere.

### 2.3 Required for each scenario

| Scenario | Required infra |
|----------|----------------|
| **API starts** | Postgres. Optional: MinIO (can disable); others optional or lazy. |
| **Snapshot ingest (in-process)** | Postgres, MinIO. |
| **Research → system-flows-research.md** | Postgres, MinIO, OpenSearch, Neo4j, Qdrant (or stub embeddings), and **either** vLLM **or** Ollama. Graph/indices must already contain data (from pipeline or pre-load). |

---

## 3. Configuration and profiles

| Profile | Where | Effect |
|---------|--------|--------|
| **default** | API `application.yml` | Postgres 5432 (or env), MinIO, OpenSearch, Neo4j, Qdrant, vLLM, TEI, MLflow; `dispatch.mode=stub`. |
| **local** (API) | `application-local.yml` | Port 9080, Postgres 55432, `dispatch.mode=in-process`, Ollama (base-url, chat-model). |
| **local** (orchestrator) | orchestrator `application-local.yml` | H2 in-memory, no Flyway. |

---

## 4. Pipeline flow

### 4.1 In-process (API, `dispatch.mode=in-process`)

1. **Snapshot:** `POST /api/v1/snapshots/master` → snapshot worker → clone (file:// or GitHub), classify, upload to MinIO, write metadata to Postgres.
2. **Log ingest (optional):** `POST /api/v1/logs/ingest` → blob worker (Azure).
3. **Research:** `POST /api/v1/research/run` → research pipeline: get run → `FlowCandidateBuilder.buildCandidates(snapshotId)` (Neo4j + OpenSearch + Qdrant + MinIO) → `SynthesisPipeline.synthesize(...)` (LLM) → `OutputPublisher.publish(...)` → MinIO `output/system-flows-research/{snapshotId}/system-flows-research.md`.

Parsing, graph build, embedding, anomaly, pattern-mining, GNN, and classifier **do not run** inside the API. They are intended to run in the Argo pipeline (separate pods with `pipeline.jar`). So for in-process research, **evidence must already exist** in Neo4j, OpenSearch, and Qdrant (and MinIO); otherwise candidates may be empty and the output minimal.

### 4.2 Argo (K8s)

Workflow defines stages: clone → parse (code, logs, topology) → index OpenSearch, build graph → anomaly, sequence mining → embed → (optional) GNN, classifier → build-candidates → synthesize → publish → evaluate. Each step uses `flowforge-pipeline-cpu` or `flowforge-pipeline-gpu` and `--stage=...`. The JAR/image for that pipeline is **not** produced by this repo.

---

## 5. Summary tables

### Table 1: Components — Ready / Partial / Not ready

| Component | Status | Reason |
|-----------|--------|--------|
| API | **Ready** | Starts with Postgres; in-process snapshot + research. |
| Orchestrator | **Ready** | Argo submit/monitor; no pipeline image in repo. |
| Pipeline service | **Not ready** | No module; Argo expects external pipeline image. |
| common, ingest, code-parser, log-parser, graph, anomaly, embedding, reranker, retrieval, llm, synthesis, publisher, mlflow, evaluation, dapr, observability | **Ready** | Implemented. |
| topology, pattern-mining, vector-store, flow-builder, gnn, classifier | **Partial** | Stubs or conditional/empty paths. |

### Table 2: Infrastructure — Ready / Partial / Not ready

| Infrastructure | Status | Notes |
|----------------|--------|--------|
| PostgreSQL | **Ready** | Compose + K8s; required for API and jobs. |
| MinIO | **Ready** | Compose + K8s; required for snapshot and output. |
| OpenSearch | **Ready** | Compose + K8s; required for retrieval/candidates. |
| Neo4j | **Ready** | Compose + K8s; required for flow candidates. |
| Qdrant | **Ready** | Compose + K8s; stub embeddings if TEI missing. |
| Redis | **Partial** | In Compose; Dapr uses it in K8s but no Argo app. |
| MLflow | **Partial** | In config; not in Compose or Argo; optional. |
| Ollama | **Partial** | Local profile + Argo; not in Compose. |
| vLLM | **Partial** | Config + Argo; not in Compose; needed if no Ollama. |
| TEI | **Partial** | Config + Argo; not in Compose; optional (stubs if absent). |

---

*Generated from codebase analysis. Last updated: 2026-03-10.*
