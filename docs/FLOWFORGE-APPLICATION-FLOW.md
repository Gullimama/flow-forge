## FlowForge Application Flow

This document describes in detail how the FlowForge system is expected to work end‑to‑end: what components exist, how they interact, and what the typical flows look like from a user’s point of view and from the platform’s point of view.

The description below assumes the codebase as of Stage 20+ (through LLM and initial synthesis/publisher work), plus later orchestration/observability stages where relevant.

---

## 1. High‑level architecture

### 1.1 Core components

- **API service (`services/api`)**
  - Spring Boot app exposing HTTP endpoints for:
    - Snapshot management: create baseline/refresh snapshots of Git repos.
    - Log ingestion: trigger log batch ingest from Azure Blob.
    - Research runs: start a research run for a snapshot and retrieve its status or output.
    - Job status: check progress of long‑running operations.
    - Download of the final research report (`system-flows-research.md`) once available.
  - Talks to:
    - PostgreSQL (operational metadata and job tracking).
    - MinIO (raw/parsed evidence, synthesis output).
    - Neo4j (graph).
    - OpenSearch (code/log indexes).
    - Qdrant (vector store).
    - LLM backends (vLLM or Ollama) via Spring AI.

- **Orchestrator service (`services/orchestrator`)**
  - Spring Boot app that wraps the Argo Workflows API.
  - Offers `/api/v1/pipelines` endpoints to submit, inspect, and monitor Argo workflows.
  - Responsible for running the *full* multi‑stage pipeline (clone → parse → index → embeddings → candidates → synthesis → publish) in Kubernetes.

- **Library modules (`libs/*`)**
  - `libs/common`: config, entities, JPA repositories, MinIO client, OpenSearch wrapper, Flyway migrations.
  - `libs/ingest`: GitHub snapshot ingest and Azure Blob log ingest.
  - `libs/code-parser` / `libs/log-parser` / `libs/topology` / `libs/graph` / `libs/anomaly` / `libs/pattern-mining`: build semantic evidence (code, logs, topology, graph, anomalies, patterns).
  - `libs/vector-store` / `libs/embedding` / `libs/reranker` / `libs/retrieval`: vector + BM25 + graph retrieval and reranking.
  - `libs/flow-builder`: builds flow candidates from graph + retrieval evidence.
  - `libs/llm`: LLM configuration and generation (ChatModel, prompts, structured output).
  - `libs/synthesis`: multi‑stage LLM synthesis over flow candidates.
  - `libs/publisher`: generates and publishes the final Markdown report.
  - `libs/dapr`, `libs/observability`, `libs/evaluation`, etc.: integration, telemetry, evaluation.

- **Infrastructure**
  - **PostgreSQL**: job metadata, snapshots, blob batches/records, research runs, parse artifacts.
  - **MinIO**: object storage for:
    - `raw-git` (cloned source snapshots),
    - `raw-logs` (archives + extracted logs),
    - `parsed-code` / `parsed-logs` / `graph-artifacts` / `research-output` / `model-artifacts`,
    - `evidence` (intermediate analysis artifacts),
    - `output` / `system-flows-research/{snapshotId}/system-flows-research.md`.
  - **OpenSearch**: indexes for code, config, runtime events, anomaly episodes.
  - **Neo4j**: knowledge graph (services, endpoints, edges, data stores).
  - **Qdrant**: vector store for code and log embeddings.
  - **ML/LLM infra**:
    - TEI (Text Embeddings Inference) for code/log embeddings and cross‑encoder reranker.
    - vLLM (Qwen Coder) or Ollama for LLM chat/generation.
  - **Argo Workflows + Dapr (K8s)** for distributed orchestration and cross‑service communication.

---

## 2. Core user‑visible flows

At a high level, users interact with FlowForge via **two main surfaces**:

1. The **API service**, which exposes REST endpoints for:
   - snapshot creation,
   - log ingestion,
   - launching a research run,
   - downloading the final report.
2. The **Orchestrator service**, which manages long‑running Argo workflows (pipeline runs).

The actual analysis can run in **two modes**:

- **In‑process (API)**: quick, single‑pod runs for snapshot + research, primarily for dev/local/demo.
- **Pipeline (Argo)**: full multi‑stage pipeline in K8s, each stage in its own pod.

The rest of this document describes these flows in detail.

---

## 3. In‑process flow (API, `dispatch.mode=in-process`)

This mode is typically used in local testing or small demos. The API service dispatches jobs onto virtual threads inside the same JVM and calls worker beans directly.

### 3.1 Snapshot ingest (GitHub)

**Endpoint:** `POST /api/v1/snapshots/master` (baseline) or `POST /api/v1/snapshots/refresh` (incremental).

1. **Client call**
   - User posts a `SnapshotRequest` (repo URL, optional token) to the API.
   - API validates input and creates a `JobEntity` record with type `SNAPSHOT` or `SNAPSHOT_REFRESH` in PostgreSQL via `MetadataService`.
   - API responds `202 Accepted` with a `jobId` for polling.

2. **Dispatch**
   - If `flowforge.dispatch.mode=in-process`, `InProcessJobDispatcher` starts a virtual thread, marks the job `RUNNING`, and calls the `snapshotWorker` `Runnable` bean.
   - `JobContextHolder` is populated with `jobId` and parameters so workers can look up context.

3. **Worker execution (`GitHubSnapshotWorker`)**
   - Clones or sparse‑checks out the GitHub repo via `GitHubSnapshotClient` (JGit) into a temp directory.
   - Runs `FileClassifier` over the tree:
     - Detects file types (Java, config, manifests, Helm, Docker, etc.).
     - Maps files to services/modules by path conventions (e.g. `services/booking/...` → `booking`).
   - Uploads files to MinIO under:
     - `raw-git/<snapshotId>/source/<service>/...`
     - `raw-git/<snapshotId>/manifests/...`
   - Computes content hashes and writes snapshot metadata (`SnapshotEntity`) and parse artifacts (`ParseArtifactEntity`) to PostgreSQL via `MetadataService`.
   - For **refresh** snapshots:
     - Determines changed files between previous commit SHA and current HEAD.
     - Only clones/handles changed files and records `changed_files` in the snapshot metadata.

4. **Completion**
   - If the worker succeeds, `InProcessJobDispatcher` marks the job `COMPLETED` with progress 100%.
   - On exceptions, the job is marked `FAILED` with an error message.
   - Clients can poll `GET /api/v1/jobs/{jobId}` for status.

### 3.2 Log ingest (Azure Blob → MinIO)

**Endpoint:** `POST /api/v1/logs/ingest` with `LogIngestRequest` (storage account/container/prefix, mode FULL/INCREMENTAL).

1. **Client call**
   - API validates and creates a `LOG_INGEST` job.
   - Responds `202 Accepted` with `jobId`.

2. **Dispatch**
   - In process, dispatcher starts a virtual thread and calls `blobIngestionWorker`.

3. **Worker execution (`BlobIngestionWorker`)**
   - Uses `AzureBlobClient` to:
     - List blobs in the given container/prefix.
     - Fetch blob properties (etag, size, lastModified).
   - Creates a `blob_ingestion_batch` record (`BlobBatchEntity`) in PostgreSQL.
   - **Full mode:**
     - For each blob:
       - If etag has already been seen in previous batches, skip (idempotent).
       - Download to a temp file.
       - Upload the archive into MinIO at `raw-logs/<batchId>/<blobName>/archive.zip` (or `.gz`, `.tar.gz`, etc.).
       - Extract using `ZipExtractor`:
         - Supports `.zip`, `.gz`, `.tar.gz`, and plain text logs.
         - Enforces zip‑bomb and path‑traversal protections.
       - Classify extracted files as `APP` / `ISTIO` / `UNKNOWN`.
       - Upload extracted logs to MinIO under `raw-logs/<batchId>/<blobName>/extracted/`.
       - Insert `blob_records` rows with status, log type, etag, size, timestamps.
   - **Incremental mode:**
     - Compares blobs against previous batches via `existsByEtag`.
     - Only processes new or changed blobs.

4. **Completion**
   - Batch status is set to `COMPLETED` or `FAILED`.
   - The job status is updated accordingly and can be polled via the job API.

### 3.3 Research run (build flows → LLM synthesis → publish report)

**Endpoint:** `POST /api/v1/research/run` with `ResearchRunRequest` (snapshotId, optional blobBatchId).  
**Output:** a published `system-flows-research.md` in MinIO and a link via the API.

> Note: In‑process research only makes sense when **evidence already exists** in Neo4j, OpenSearch, Qdrant, and MinIO (e.g. from a previous Argo pipeline run or manual pre‑loading), because the API does not run parse/index/embedding stages itself.

1. **Client call**
   - API validates the request, ensures the snapshot exists, and creates a `RESEARCH` job and `research_run` record in PostgreSQL.
   - Responds `202 Accepted` with `jobId`.

2. **Dispatch**
   - In `in-process` mode, dispatcher attempts to call a `researchPipeline` `Runnable` bean.
   - The expected flow inside the research pipeline is:
     - Look up the snapshot and relevant evidence.
     - Build flow candidates.
     - Run synthesis stages.
     - Publish the report.

3. **Flow candidate building (`FlowCandidateBuilder`)**
   - Uses:
     - Neo4j graph queries to find:
       - Entrypoint endpoints (HTTP handlers without upstream callers).
       - Call chains across services, including HTTP and Kafka edges.
     - OpenSearch indices (`code-artifacts`, `runtime-events`) and pattern‑mining outputs to enrich flows.
   - Builds `FlowCandidate` objects:
     - Flow type (SYNC_REQUEST, ASYNC_EVENT, MIXED, ERROR_HANDLING, etc.).
     - Ordered `FlowStep`s (HTTP endpoints, HTTP client calls, Kafka produce/consume).
     - Involved services.
     - References into evidence (code snippets, log patterns, graph paths, sequence patterns, anomalies).
   - Merges overlapping flows and scores each candidate for confidence/complexity.
   - Writes candidate evidence to MinIO (e.g. `evidence/flow-candidates/{snapshotId}.json`).

4. **Hybrid retrieval (`HybridRetrievalService`)**
   - Given a flow or step, retrieves additional context:
     - Vector code/log candidates from Qdrant (via `VectorRetriever`).
     - BM25 matches from OpenSearch (via `BM25Retriever`).
     - Graph‑based context from Neo4j.
   - Fuses these sources using Reciprocal Rank Fusion (RRF).
   - Optionally reranks with the TEI cross‑encoder reranker.

5. **LLM synthesis (`libs/llm` + `libs/synthesis`)**
   - `LlmGenerationService` provides:
     - Free‑form generation (for narrative parts).
     - Structured generation via `StructuredOutputService` and `BeanOutputConverter` (for typed JSON outputs).
   - `PromptTemplateManager` loads prompt templates:
     - `flow-analysis`, `code-explanation`, `migration-risk`, `dependency-analysis`, `reactive-complexity`, `synthesis-stage1`..`stage6`.
   - `Synthesis` pipeline:
     - **Stages 1–3** (FlowAnalysis, CodeExplanation, RiskAssessment) per `FlowCandidate`:
       - Build an LLM context from candidate + evidence.
       - Call the LLM with structured prompts.
       - Parse answers into typed records and store them in MinIO (`evidence/synthesis/stage{1,2,3}/{snapshotId}/{candidateId}.json`).
     - **Stages 4–6** (Dependency mapping, Migration plan, Final narrative):
       - Use prior stage outputs as context.
       - Generate dependency views, phased migration plans, and final narrative text (with diagram specs).
       - Store results in MinIO (`stage4`, `stage5`, `stage6` directories).
     - `SynthesisPipeline` aggregates all stages into a `SynthesisFullResult` per flow and a summary per snapshot.

6. **Publishing (`OutputPublisher`)**
   - `DocumentAssembler`:
     - Assembles all synthesis results into a `ResearchDocument` model:
       - Executive summary (overview, counts, top findings, recommended approach).
       - Per‑flow sections (analysis, code explanation, risk assessment, dependency mapping, migration plan, narrative).
       - Risk matrix and migration roadmap.
     - Builds anchors and slugs for table of contents.
   - `DocumentRenderer`:
     - Uses FreeMarker templates (`research-document.ftl`, `flow-section.ftl`) to render Markdown.
   - `OutputPublisher`:
     - Writes:
       - Markdown to MinIO: `output/system-flows-research/{snapshotId}/system-flows-research.md`.
       - JSON document model to: `output/system-flows-research/{snapshotId}/document.json`.
     - Updates metrics (documents published).

7. **Completion and retrieval**
   - Job is marked `COMPLETED` or `FAILED`.
   - User can:
     - Poll `/api/v1/jobs/{jobId}` for status.
     - Use research‑specific endpoints (e.g. `GET /api/v1/research/{runId}` or download endpoint) to fetch `system-flows-research.md`, which is streamed from MinIO by `ResearchController`.

---

## 4. Argo pipeline flow (Kubernetes)

The **Argo pipeline** is the full‑fidelity path used in cluster environments. It is orchestrated by Argo Workflows and the Orchestrator service.

### 4.1 Workflow shape

The Argo workflow (see `k8s/argo/flowforge-pipeline.yaml`) defines a DAG roughly as:

1. **Source ingestion**
   - `clone-repos`: clone all relevant Git repositories.
   - `fetch-logs`: pull Splunk log archives from Azure Blob.
   - `fetch-manifests`: fetch deployment manifests if needed.

2. **Parsing and indexing**
   - `parse-code`: run code parser; write parsed structures, index into OpenSearch (`code-artifacts`) and optionally Neo4j.
   - `parse-logs`: run log parser; normalize logs, index into `runtime-events`.
   - `parse-topology`: build service topology from manifests and code; update Neo4j.

3. **Graph and anomaly analysis**
   - `build-knowledge-graph`: load parsed code/topology into Neo4j.
   - `detect-anomalies`: run anomaly detection; write episodes into OpenSearch.
   - `mine-sequences`: run SPMF PrefixSpan over call sequences; store patterns in MinIO and OpenSearch.

4. **Embeddings and optional ML**
   - `embed-code` / `embed-logs`: compute embeddings via TEI and write vectors into Qdrant.
   - `gnn-analysis` (optional): run graph neural net modules if configured.

5. **Synthesis pipeline**
   - `classify-migration` / `build-candidates`: run `FlowCandidateBuilder` to produce candidates.
   - `synthesize`: run synthesis stages 1–6 using LLM + retrieval.
   - `publish-output`: render and upload `system-flows-research.md`.
   - `evaluate`: run evaluation metrics.

Each task is run as a separate pod using `flowforge-pipeline-cpu` or `flowforge-pipeline-gpu` images (not built by this repo).

### 4.2 Orchestrator service (`services/orchestrator`)

**Endpoints:**

- `POST /api/v1/pipelines`
  - Accepts a `PipelineRequest` (snapshotId, repo URLs, log time range, whether to run GNN, etc.).
  - Submits the Argo workflow by:
    - Loading the YAML template.
    - Filling in `spec.arguments.parameters` with the request.
    - POSTing to the Argo Workflows API.
  - Returns an initial `WorkflowStatus` (name, phase, timestamps).

- `GET /api/v1/pipelines/{name}`
  - Returns the current status (phase) of the workflow.

- `GET /api/v1/pipelines/{name}/tasks`
  - Returns the status of individual DAG nodes/tasks (phase, timestamps, messages).

**Service internals:**

- `ArgoWorkflowService`:
  - Handles REST calls to Argo.
  - Implements `submitPipeline`, `getStatus`, `getTaskStatuses`, and `waitForCompletion`.
  - Emits metrics such as `flowforge.argo.workflow.submitted` and `flowforge.argo.workflow.completed`.

### 4.3 Relationship to the API

- The API can:
  - Trigger a snapshot and research run **in‑process** (for small/test runs).
  - Or, the Orchestrator can trigger the full Argo pipeline which populates all evidence and ultimately writes `system-flows-research.md`.
- In both cases, the final report is stored in MinIO and can be downloaded through the API’s research endpoints.

---

## 5. Typical end‑to‑end scenarios

### 5.1 Local, in‑process demo

1. Start local infra via Docker Compose (Postgres, MinIO, OpenSearch, Neo4j, Qdrant).
2. Run API with `local` profile and `dispatch.mode=in-process`.
3. Call `POST /api/v1/snapshots/master` pointing at a local or GitHub repo.
4. (Optional) Pre‑load or run a subset of pipeline tasks to populate OpenSearch/Neo4j/Qdrant.
5. Call `POST /api/v1/research/run` with the `snapshotId`.
6. Poll job status and then call the research output endpoint to download `system-flows-research.md`.

### 5.2 Full cluster pipeline

1. Use ArgoCD to deploy infra (Postgres, MinIO, OpenSearch, Neo4j, Qdrant, TEI, vLLM, MLflow, etc.) and FlowForge services.
2. From Orchestrator, call `POST /api/v1/pipelines` with appropriate parameters.
3. Monitor the workflow via:
   - Orchestrator endpoints,
   - Argo UI,
   - Prometheus/Grafana dashboards.
4. Once the workflow completes, use the API service to:
   - Query the latest research run for a snapshot.
   - Download `system-flows-research.md` from the `output` bucket.

---

## 6. Summary

FlowForge’s expected behaviour is:

- Ingest code and logs from Git/GitHub and Azure Blob.
- Build rich evidence (parsers, graph, anomalies, sequences, embeddings).
- Discover candidate flows across services (HTTP/Kafka).
- Retrieve contextual evidence for each flow (vector, BM25, graph).
- Run a multi‑stage LLM synthesis pipeline to analyze flows and design migration plans.
- Publish a single, evidence‑backed research document (`system-flows-research.md`) plus JSON artefacts to MinIO.

The application can run a minimal variant in‑process via the API or the full multi‑stage variant via Argo Workflows in Kubernetes; in both cases, the external behaviour for users centers around a small set of REST endpoints and the final research report.

