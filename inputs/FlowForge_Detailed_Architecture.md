# FlowForge — Detailed Architecture

## 1. Purpose

**FlowForge** is an internal AKS-hosted research platform for analyzing a large Java 11, Micronaut-based reactive microservice estate and producing a **condensed, evidence-backed system flow research document**.

This phase is **research-only**. It does **not** generate the rewritten code yet.

The platform will:

- Pull a **master branch snapshot** from enterprise GitHub on demand
- Refresh only on explicit API trigger
- Ingest **all available Splunk log zip files** from an **Azure Blob Storage folder**
- Parse source, configs, manifests, and runtime logs
- Build a combined **search + vector + graph** knowledge base
- Generate one canonical markdown output:
  - `system-flows-research.md`

This output becomes the foundation for a later rewrite into:

- Java 25
- Spring
- Dapr
- Virtual threads
- Non-reactive execution model

---

## 2. Updated Assumptions

### Source code inputs
1. Private enterprise GitHub is already reachable from AKS
2. A GitHub token with read-only access is available
3. Snapshot is always taken from the **master** branch
4. Refresh occurs only when a specific API is called

### Log inputs
1. Splunk logs are already safe for internal processing
2. No further redaction is required
3. Application logs share a common format
4. Istio logs use a different format
5. **Log time window is irrelevant**
6. The system should ingest **whatever zip files exist**
7. Those zip files are stored in an **Azure Blob Storage folder**

### Security scope
1. AKS environment is private and secured
2. All processing services are internal-only
3. No additional security controls are required for this phase

---

## 3. Key architecture changes from the earlier version

The architecture is updated in the following important ways:

### 3.1 Logs are no longer time-bound
There is **no 3-month assumption** and no date-window filtering requirement.

FlowForge will:
- ingest **all available zip files** from the configured Azure Blob folder
- treat them as a bulk evidence dataset
- parse and index them as-is
- extract runtime paths from the entire available corpus

### 3.2 Azure Blob Storage is the log source
Instead of user-uploaded or manually copied logs, FlowForge will read log bundles directly from an Azure Blob folder.

### 3.3 Log ingestion is dataset-based, not stream-based
This is a **batch ingestion architecture**, not a live log pipeline.

### 3.4 Runtime understanding remains important
Even though logs are just bulk zip files, they still provide:
- true service-to-service runtime edges
- ingress paths
- latency hotspots
- failure paths
- retries and fallbacks
- hidden coupling

---

## 4. Final architecture principles

FlowForge is designed around the following principles:

### 4.1 Graph-first research
Vector search helps locate evidence, but actual flow reconstruction should be graph-driven.

### 4.2 Static + runtime fusion
Source code alone is incomplete.
Logs alone are incomplete.
The truth emerges by reconciling both.

### 4.3 Incremental code refresh
Code refresh happens only when explicitly triggered and only changed areas are reprocessed.

### 4.4 Bulk log evidence ingestion
All zip files in the configured Azure Blob folder are treated as the available runtime evidence set.

### 4.5 Output optimized for future migration
The research output should already help the later Java 25 + Spring + Dapr + virtual thread rewrite.

---

## 5. Platform name

## Recommended name: **FlowForge**

Why it fits:
- **Flow** = your current goal is deep software flow research
- **Forge** = it creates the foundation for later modernization and rewrite

---

## 6. High-level solution overview

FlowForge consists of six major layers:

1. **Control Plane**
2. **Ingestion Layer**
3. **Parsing & Enrichment Layer**
4. **Knowledge Stores**
5. **Research & Synthesis Layer**
6. **Output Layer**

---

## 7. High-level architecture diagram

```text
                                  +----------------------+
                                  |   FlowForge API      |
                                  |  Trigger / Control   |
                                  +----------+-----------+
                                             |
          +----------------------------------+----------------------------------+
          |                                                                     |
          v                                                                     v
+-------------------------------+                                   +-------------------------------+
| GitHub Snapshot Ingestion     |                                   | Azure Blob Log Ingestion      |
| - master branch clone/fetch   |                                   | - list zip files             |
| - baseline snapshot           |                                   | - download zip files         |
| - delta refresh               |                                   | - extract and normalize      |
+---------------+---------------+                                   +---------------+---------------+
                |                                                                   |
                v                                                                   v
                         +-------------------------------------------------+
                         |                Raw Evidence Store                |
                         |                     MinIO                        |
                         +------------------+------------------------------+
                                            |
                                            v
                         +-------------------------------------------------+
                         |             Parsing & Enrichment                 |
                         | - Java/Micronaut parser                         |
                         | - Config/manifest parser                        |
                         | - Istio topology parser                         |
                         | - App log parser                                |
                         | - Istio log parser                              |
                         | - Flow candidate builder                        |
                         +-------------+---------------+-------------------+
                                       |               | 
                +----------------------+               +----------------------+
                |                                                             |
                v                                                             v
      +---------------------+                                  +----------------------+
      | OpenSearch          |                                  | Qdrant               |
      | exact search        |                                  | semantic retrieval   |
      | log analytics       |                                  | embeddings           |
      +----------+----------+                                  +----------+-----------+
                 \                                                        /
                  \                                                      /
                   \                                                    /
                    v                                                  v
                           +--------------------------------------+
                           |              Neo4j                   |
                           |       service / runtime graph        |
                           +----------------+---------------------+
                                            |
                                            v
                           +--------------------------------------+
                           |     Research & Synthesis Engine      |
                           | - retrieve evidence                  |
                           | - reconstruct flows                  |
                           | - reconcile code + runtime           |
                           | - generate markdown                  |
                           +----------------+---------------------+
                                            |
                                            v
                           +--------------------------------------+
                           |      system-flows-research.md        |
                           +--------------------------------------+
```

---

## 8. AKS namespace layout

### `flowforge-api`
Contains:
- FlowForge control API
- sync trigger endpoints
- research trigger endpoints
- result retrieval endpoints

### `flowforge-ingest`
Contains:
- GitHub sync workers
- Azure Blob log ingestion workers
- zip extraction jobs

### `flowforge-process`
Contains:
- source parsing workers
- config/manifests analyzers
- log normalization workers
- graph construction workers
- embedding workers
- markdown generator

### `flowforge-data`
Contains:
- MinIO
- OpenSearch
- Qdrant
- Neo4j
- PostgreSQL metadata store

### `flowforge-llm`
Contains:
- model serving
- embedding model service
- generation model service

### `flowforge-observe`
Contains:
- Prometheus
- Grafana
- OpenTelemetry Collector for FlowForge itself

---

## 9. Core components

## 9.1 FlowForge API

This is the control plane entry point.

### Responsibilities
- accept sync requests
- trigger GitHub baseline snapshot
- trigger incremental refresh
- trigger Azure Blob log ingestion
- launch full research runs
- expose status and result retrieval

### Suggested API endpoints

#### Sync APIs
- `POST /api/v1/snapshots/master`
- `POST /api/v1/snapshots/refresh`

#### Log APIs
- `POST /api/v1/logs/ingest`
- `POST /api/v1/logs/reindex`

#### Research APIs
- `POST /api/v1/research/run`
- `GET /api/v1/research/latest`
- `GET /api/v1/research/{runId}`
- `GET /api/v1/research/{runId}/artifact`

#### Status APIs
- `GET /api/v1/jobs/{jobId}`
- `GET /api/v1/health`

---

## 9.2 GitHub Snapshot Ingestion Service

### Purpose
To create a reproducible code snapshot from the master branch and support refreshes only on demand.

### Responsibilities
- connect to enterprise GitHub using supplied token
- fetch the latest master branch state
- create an initial baseline snapshot
- store snapshot metadata
- on refresh, detect changed files/modules since prior snapshot
- reprocess only impacted services/components

### Snapshot model
Each snapshot should record:
- repository URL
- branch = master
- commit SHA
- snapshot ID
- creation timestamp
- snapshot type = baseline or refresh
- changed files list (for refresh)

### Outputs to raw storage
- source code
- build files
- manifests
- Helm charts
- Kubernetes YAML
- Istio YAML
- OpenAPI files
- schemas
- configuration files

---

## 9.3 Azure Blob Log Ingestion Service

### Purpose
To ingest all available Splunk log zip files from an Azure Blob folder.

### Responsibilities
- connect to configured Azure Blob Storage account/container/folder
- list all zip files in the folder
- optionally compare with previously ingested blob etags or names
- download new or requested zip files
- extract contents
- classify log type:
  - application logs
  - Istio logs
- hand off extracted content to the parser pipeline

### Important design choice
This service should **not** care about time windows.

It should treat the Azure Blob folder as:
- the current bulk evidence location
- the authoritative log dataset source for the research run

### Blob ingestion modes
#### Mode A — Full ingest
- used for first-time or full rebuilds
- scans all blob zip files in folder

#### Mode B — Incremental ingest
- used later if desired
- ingests only newly discovered blobs or changed blobs

### Suggested blob tracking metadata
- storage account
- container
- virtual folder/prefix
- blob name
- etag
- content length
- last modified timestamp
- ingestion batch ID

---

## 9.4 Zip Extraction Service

### Purpose
To unpack log archives in a deterministic way.

### Responsibilities
- unpack each zip
- preserve original archive metadata
- enumerate entries
- validate readable log files
- route entries to correct parser
- write extracted raw files to MinIO

### Output structure in raw storage
- `raw-logs/<batch-id>/<blob-name>/archive.zip`
- `raw-logs/<batch-id>/<blob-name>/extracted/...`

---

## 9.5 Static Code Parsing Service

### Purpose
To transform source code and config into structured software knowledge.

### Inputs
- Java source
- Micronaut annotations
- Maven/Gradle files
- YAML/properties
- Kubernetes manifests
- Istio resources
- API contracts

### Extracted facts
- packages
- classes
- methods
- controllers
- endpoints
- service methods
- repositories
- DTOs
- exceptions
- retries/fallback logic
- downstream client calls
- topic producers/consumers
- scheduled jobs
- config keys
- deployment identity
- ingress rules
- routing definitions

### Output
Structured JSON facts written to:
- MinIO
- OpenSearch
- Neo4j
- Qdrant-ready chunk pipeline

---

## 9.6 Runtime Log Parsing Service

### Purpose
To turn extracted logs into normalized runtime evidence.

### Two parser families

#### Application log parser
Handles the common application format.

It extracts:
- timestamp
- service
- pod/instance
- log level
- correlation ID
- request ID
- trace ID
- route
- downstream target
- exception
- duration
- business markers where available

#### Istio log parser
Handles Envoy/Istio-specific format.

It extracts:
- source workload
- destination workload
- method
- path
- response code
- response flags
- upstream/downstream timing
- bytes in/out
- protocol
- namespace/workload context

### Unified runtime event model

```json
{
  "eventId": "uuid",
  "timestamp": "ISO-8601",
  "sourceType": "app|istio",
  "service": "string",
  "namespace": "string",
  "pod": "string",
  "traceId": "string",
  "spanId": "string",
  "correlationId": "string",
  "requestId": "string",
  "httpMethod": "string",
  "path": "string",
  "statusCode": 200,
  "latencyMs": 0,
  "targetService": "string",
  "exceptionType": "string",
  "message": "string",
  "tags": {}
}
```

### Output
Normalized runtime events are stored in:
- OpenSearch for exact retrieval and analytics
- Neo4j for runtime edges
- Qdrant for semantic grouping of runtime evidence
- MinIO as parsed JSON evidence

---

## 9.7 Topology & Config Enrichment Service

### Purpose
To understand the deployed shape of the platform, not just the source code shape.

### Inputs
- Kubernetes manifests
- Helm values/templates
- Istio VirtualServices
- DestinationRules
- Gateways
- Sidecar or mesh configs where available

### Extracted insights
- ingress entry points
- gateway mapping
- route rewrites
- destination service mapping
- traffic segmentation
- cross-namespace interactions
- deployment topology
- dependency hints

### Why this matters
A service flow is not fully understood from source code alone.
Traffic behavior is shaped by platform routing too.

---

## 9.8 Flow Candidate Builder

### Purpose
To identify likely business and technical flows.

### Candidate sources
- public endpoints
- scheduled jobs
- message consumers
- common runtime sequences
- repeated failure signatures
- high-frequency ingress paths

### Example candidate
`POST /booking/confirm`

Possible reconstructed flow:
- gateway receives request
- booking controller invoked
- booking service orchestrates
- downstream pricing call
- downstream inventory call
- event publish
- DB update
- response sent

### Candidate types
- request/response flow
- asynchronous event flow
- scheduled/background flow
- failure/recovery flow

---

## 9.9 Knowledge Graph Builder

### Purpose
To create the authoritative relationship model.

### Node types
- Repository
- Module
- Service
- Class
- Method
- Endpoint
- Topic
- Consumer
- Database
- Table
- ConfigKey
- ExternalDependency
- RuntimeEvent
- ErrorType
- Flow

### Edge types
- CONTAINS
- EXPOSES
- CALLS
- DEPENDS_ON
- ROUTES_TO
- READS
- WRITES
- PUBLISHES
- CONSUMES
- OBSERVED_AS
- FAILS_WITH
- PRECEDES
- PART_OF_FLOW

### Why graph storage is essential
Vector search can tell us what is similar.
The graph tells us what is connected.

For software-flow reconstruction, graph structure is the real backbone.

---

## 9.10 Embedding & Semantic Retrieval Service

### Purpose
To support semantic search and evidence gathering.

### Embed these entities
- code chunks
- class summaries
- method summaries
- endpoint summaries
- config fragments
- runtime log windows
- clustered error patterns
- generated flow descriptions

### Collections
- `code_chunks`
- `endpoint_summaries`
- `log_windows`
- `error_patterns`
- `flow_summaries`

### Important note
Embeddings support discovery and evidence retrieval.
They are not the only truth source.

---

## 9.11 Research & Synthesis Engine

### Purpose
To generate the final research artifact.

### Responsibilities
- retrieve static evidence
- retrieve runtime evidence
- retrieve graph paths
- reconcile contradictions
- assign confidence levels
- generate markdown sections
- flag unknowns and gaps

### Research strategy
For each major flow:
1. identify entry point
2. reconstruct probable code path
3. check runtime evidence
4. check mesh/routing evidence
5. identify data access or downstream dependencies
6. summarize the flow
7. classify migration signals
8. assign confidence

---

## 10. Storage architecture

FlowForge should use **four complementary storage views**.

## 10.1 MinIO — raw evidence lake

### Stores
- raw Git snapshots
- raw blob zip files
- extracted log files
- parsed code JSON
- parsed log JSON
- graph export artifacts
- markdown outputs

### Bucket layout
- `raw-git`
- `raw-logs`
- `parsed-code`
- `parsed-logs`
- `graph-artifacts`
- `research-output`

---

## 10.2 OpenSearch — exact search and analytics

### Use for
- source/config full-text search
- runtime log search
- filtering by service/endpoint/error
- aggregations
- top path analysis
- error count analysis
- route-based evidence lookup

### Typical indexes
- `code-artifacts`
- `config-artifacts`
- `runtime-events`
- `flow-metadata`
- `job-metadata`

---

## 10.3 Qdrant — vector memory

### Use for
- semantic evidence retrieval
- similar code fragment discovery
- similar error flow grouping
- flow similarity comparison
- context retrieval during markdown generation

---

## 10.4 Neo4j — canonical flow graph

### Use for
- inter-service dependencies
- method and endpoint relationships
- observed runtime paths
- multi-hop dependency exploration
- reconstructed flow validation

---

## 10.5 PostgreSQL — operational metadata

### Use for
- job tracking
- run history
- snapshot metadata
- blob ingestion metadata
- artifact versions
- parser status
- model run metadata

---

## 11. Orchestration model

## Recommended orchestration split

### Dapr
Use for:
- workflow coordination
- service invocation
- pub/sub messaging between internal services
- state/checkpoint handling

### Argo Workflows
Use for:
- long-running batch pipelines
- large ingest jobs
- reprocessing pipelines
- operationally visible step orchestration

### Suggested practical choice
Use:
- **Dapr** inside the application runtime for service orchestration and internal workflow state
- **Argo Workflows** for heavy batch pipelines and operational visibility

This gives a better operational model than forcing everything into one orchestration layer.

---

## 12. End-to-end execution flows

## 12.1 Initial baseline run

1. Call `POST /api/v1/snapshots/master`
2. GitHub sync service fetches master branch
3. Baseline snapshot is stored
4. Call `POST /api/v1/logs/ingest`
5. Azure Blob log service scans configured folder
6. All zip files are downloaded and extracted
7. Code parsing runs
8. Log parsing runs
9. Topology enrichment runs
10. Graph build runs
11. Embedding pipeline runs
12. Research generation runs
13. `system-flows-research.md` is published

---

## 12.2 Refresh run

1. Call `POST /api/v1/snapshots/refresh`
2. Latest master SHA is fetched
3. Changed files/modules are detected
4. Only impacted code sections are reparsed
5. Graph and embeddings are partially updated
6. If logs are re-ingested, only new/changed blobs are imported
7. Research artifact is regenerated
8. New version is published

---

## 13. Recommended research output contract

The main output should be one canonical markdown document:

## `system-flows-research.md`

### Recommended sections

```md
# System Flows Research

## 1. Estate Overview
## 2. Service Catalog
## 3. Deployment and Routing Topology
## 4. Inter-Service Runtime Map
## 5. Major Functional Flows
## 6. Scheduled and Background Flows
## 7. Failure and Recovery Flows
## 8. Reactive Complexity Hotspots
## 9. Migration Signals for Java 25 + Spring + Dapr
## 10. Gaps and Unknowns
```

### Additional supporting artifacts
Even if the primary deliverable is one markdown file, FlowForge should also store:

- `flow-catalog.json`
- `service-catalog.json`
- `runtime-edge-summary.json`
- `dependency-graph.json`
- `research-run-manifest.json`

These improve later reuse.

---

## 14. Flow documentation model

Each flow section in the markdown should follow a strict structure.

### Flow template

```md
### Flow: Booking Confirmation

**Entry type:** HTTP  
**Entry point:** POST /booking/confirm  
**Observed services:** booking-service, pricing-service, inventory-service  
**Downstream dependencies:** payment topic, booking_db  
**Confidence:** High

#### Summary
A concise description of what this flow does.

#### Static flow path
Controller -> service -> downstream clients -> repository

#### Observed runtime path
Gateway -> booking-service -> pricing-service -> inventory-service

#### Data operations
- booking_db write
- event publish

#### Failure patterns
- inventory timeout
- retry path observed

#### Modernization notes
- good candidate for virtual threads
- pricing/inventory calls map to Dapr service invocation
- payment event maps to Dapr pub/sub
```

---

## 15. Migration-aware research tags

Since the later target is already known, every flow should be classified during research with tags like:

- `sync-http`
- `async-event`
- `scheduler`
- `cpu-bound`
- `io-bound`
- `virtual-thread-candidate`
- `keep-async-candidate`
- `dapr-invocation-candidate`
- `dapr-pubsub-candidate`
- `dapr-workflow-candidate`
- `stateful-orchestration`
- `idempotency-required`
- `retry-hotspot`
- `reactive-overuse`

These tags make the research output directly reusable in the rewrite phase.

---

## 16. Why this architecture is right for your scenario

This design is specifically correct for your environment because:

### 16.1 It respects your control model
- no continuous sync
- no unnecessary refresh loops
- only explicit API-triggered updates

### 16.2 It respects your log reality
- logs are already safe
- logs have known formats
- one app format plus one Istio format
- no time-window dependency
- Azure Blob is the source of truth

### 16.3 It focuses on the real goal
The goal is not generic code search.
The goal is **deep software-flow research**.

That requires:
- static analysis
- runtime analysis
- mesh topology analysis
- graph reconstruction
- evidence-backed summarization

### 16.4 It prepares for the later rewrite
The resulting artifact can drive:
- service consolidation
- reactive simplification
- virtual-thread adoption
- Dapr pattern mapping
- Spring migration planning

---

## 17. What not to do

Do **not** build this as:
- only a vector database
- only a generic RAG chatbot
- only a code parser without runtime evidence
- only a log analytics pipeline without source understanding
- a single huge LLM prompt over the entire codebase

That would not produce trustworthy flow research.

---

## 18. Recommended implementation phases

## Phase 1 — Foundation
Build:
- FlowForge API
- GitHub master snapshot ingest
- Azure Blob zip ingest
- raw evidence storage
- OpenSearch indexes
- metadata database

### Deliverable
- baseline ingestion working

---

## Phase 2 — Understanding
Build:
- code parsing
- config/topology enrichment
- application log parser
- Istio log parser
- graph builder

### Deliverable
- structured service and runtime knowledge base

---

## Phase 3 — Semantic research
Build:
- embedding pipeline
- retrieval layer
- flow candidate builder
- research synthesis engine

### Deliverable
- first `system-flows-research.md`

---

## Phase 4 — Incremental refinement
Build:
- delta refresh from GitHub
- incremental reprocessing
- incremental blob ingestion
- better confidence scoring
- dead code / unobserved path detection

### Deliverable
- efficient repeatable operation

---

## 19. Final recommended stack

### Platform
- AKS
- Dapr
- Argo Workflows

### Data
- MinIO
- OpenSearch
- Qdrant
- Neo4j
- PostgreSQL

### Parsing
- Tree-sitter-based parsing
- custom Java/Micronaut analyzers
- YAML/manifest/Istio analyzers

### AI layer
- self-hosted embedding model
- self-hosted instruct/coding model
- vLLM serving layer

### Observability
- Prometheus
- Grafana
- OpenTelemetry Collector

---

## 20. Final one-sentence architecture summary

**FlowForge** is an AKS-hosted internal research platform that pulls on-demand master snapshots from private GitHub, ingests all available Splunk log zip files from Azure Blob Storage, fuses static code knowledge with runtime evidence into search/vector/graph stores, and generates a single condensed markdown document describing the true system flows for later modernization into Java 25, Spring, Dapr, and virtual-thread-based execution.
