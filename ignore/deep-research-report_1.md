# FlowForge revised architecture and `system-flows-research.md` specification

## Executive summary

[Download `system-flows-research.md`](sandbox:/mnt/data/system-flows-research.md)

FlowForge is best designed as a **deterministic, evidence-driven research platform** (not a generic “RAG over code”) that builds a reproducible knowledge base from two sources: an API-triggered **GitHub master snapshot** (baseline + delta refresh by commit SHA) and a **bulk set of Splunk-export ZIPs** stored in an **Azure Blob Storage container** (ingest whatever is present; no time-window semantics). citeturn1search0turn1search1

The architecture in the downloadable markdown implements the following core decisions:

- **Orchestration split:** use **Dapr Workflows** for long-running, fault-tolerant orchestration and resumability, plus **Argo Workflows / Kubernetes Jobs** for compute-heavy batch stages (parsing, embedding, graph upserts). citeturn0search0turn1search2turn4search2  
- **Four-store model:** persist raw evidence in MinIO, exact search in OpenSearch (including parsed logs), semantic retrieval in Qdrant, and relationship truth in Neo4j, with PostgreSQL keeping run/snapshot metadata and checkpoints. citeturn2search12turn4search7turn5search14turn2search1  
- **Parsing & flow reconstruction:** extract code structure using Tree-sitter-style incremental parsing foundations and custom Java analyser stages, then reconcile static call chains with runtime edges derived from app and Istio logs to assign confidence. citeturn0search1  
- **Self-hosted models:** serve generation via vLLM’s OpenAI-compatible API surface and use an open-source embedding model in-cluster (exact model sizes are explicitly left unspecified; choose per GPU/latency budgets). citeturn0search3turn5search14  
- **Rewrite-aware output:** each flow is annotated for a non-reactive rewrite path (virtual threads, Dapr suitability, idempotency/compensation needs). Virtual threads are finalised in the Java platform per JEP 444, making them a stable concurrency target for the future rewrite. citeturn4search0  

## Ingestion and refresh semantics

FlowForge’s ingestion is intentionally **API-triggered** and **versioned**, so every research output can be reproduced from a recorded snapshot/run manifest.

For GitHub master snapshots:
- Baseline run resolves the `master` head SHA and stores a MinIO “snapshot prefix” for raw artefacts plus a PostgreSQL snapshot record.
- Refresh run resolves the new `master` head SHA and computes deltas (changed files/services) by comparing base SHA → head SHA; GitHub’s REST “compare two commits” endpoint supports comparing refs and SHAs for this purpose. citeturn1search1turn1search5  

For Splunk ZIPs in Azure Blob:
- No time window is applied; FlowForge lists blobs in a container/prefix and ingests whatever exists, using an idempotent manifest keyed by `(blob_name, ETag)` so repeats are safe and refresh is incremental by “new or changed ZIP blob”. Azure Blob Storage is designed for large amounts of unstructured object data, which matches bulk ZIP ingestion well. citeturn1search0turn1search16  
- Implementation can use either the Azure SDK directly or the Dapr Azure Blob Storage binding. The Dapr binding spec explicitly supports operations including `list` and `get`, enabling an API-driven “list blobs → download ZIPs” workflow without continuous event triggers. citeturn6view0turn5search0  

## Storage and knowledge model

The revised design uses each store for what it is best at:

- **MinIO** is the immutable evidence lake (raw Git snapshots, raw ZIPs, extracted files, run outputs). MinIO’s S3 compatibility makes it practical for “evidence object keys” and reprocessing workflows. citeturn2search12turn2search0  
- **OpenSearch** is the deterministic search/analytics layer. It is Apache-2.0 licensed and supports vector ingestion at the mapping level via `knn_vector`, though FlowForge still uses Qdrant as the primary vector database to keep responsibilities clean. citeturn4search7turn1search3  
- **Qdrant** is the semantic retrieval layer. Its core abstraction is a “collection” of “points” (vector + optional payload), which aligns well with storing code chunks and log windows with rich metadata payloads. citeturn5search7turn5search3  
- **Neo4j** is the relationship truth. Neo4j uses a property graph model (nodes + relationships), which is appropriate for representing services, endpoints, call edges, topics, data stores, Istio routing and observed runtime edges as a traversable flow graph. citeturn2search1turn2search5  
- **PostgreSQL** remains the control-plane metadata store (snapshots, runs, manifests, checkpoints).

The downloadable markdown defines:
- canonical IDs (`snapshot_id`, `run_id`, `service_id`, `flow_id`)
- evidence pointers that tie every flow claim to specific artefacts (code path + SHA, manifest object, log excerpt pointer)
- JSON schema sketches for `service-catalog.json`, `dependency-graph.json`, `runtime-edge-summary.json`, `flow-catalog.json`

## Orchestration and processing pipeline

FlowForge combines two orchestration tiers:

- **Dapr Workflows** coordinate long-running runs and persist workflow state, making them suitable for multi-stage pipelines that must resume cleanly after failures. citeturn0search0  
- **Argo Workflows** executes heavy DAG stages as container steps; Argo is implemented as a Kubernetes CRD and supports DAG-style workflows, which is a good match for parallel ZIP extraction/parse, parallel repo parse, and parallel embedding batches. citeturn1search2turn1search10  

Kubernetes workload primitives are selected per component:
- **StatefulSets** for MinIO/OpenSearch/Qdrant/Neo4j/PostgreSQL because StatefulSets provide stable identities and are used for workloads needing persistence or stable network identity. citeturn2search2  
- **Deployments** for stateless API/controllers because a Deployment typically manages workloads that do not maintain state. citeturn4search1  
- **Jobs** (often invoked by Argo) for run-to-completion batch steps (extract/parse/embed), since Jobs run Pods to completion and can restart failed Pods. citeturn4search2  

Incremental parsing and refresh strategy:
- Tree-sitter is explicitly designed as an incremental parsing library that builds a concrete syntax tree and updates efficiently as files change; FlowForge leverages the same principle operationally by reprocessing only changed files and affected services on refresh. citeturn0search1turn0search5  

## Output contract and rewrite-aware annotations

The system’s primary deliverable is a single file: `system-flows-research.md`, generated per run, plus the four machine-readable artefacts. The markdown must include:

- estate overview, service inventory, and an inter-service runtime map
- detailed flows with evidence and confidence scoring
- scheduled/background flows, error/recovery flows
- reactive complexity hotspots (for simplification in rewrite)
- migration signals for Java 25 + Spring + Dapr, including virtual-thread fit, sync/async classification, statefulness, idempotency, and compensation needs

Virtual threads are a stable platform feature (finalised per JEP 444), which supports the “non-reactive, virtual-thread-first” rewrite context you specified. citeturn4search0  

For Dapr suitability flags, the doc anchors on Dapr’s building blocks:
- service invocation for reliable HTTP/gRPC service-to-service calls citeturn3search2  
- pub/sub building block semantics and delivery model citeturn3search3  
- workflow orchestration for stateful long-running processes citeturn0search0  

## Operations, observability, and future hardening

The revised design includes an operational baseline for the private AKS environment and a “future hardening” section for later maturity.

Observability stack:
- **OpenTelemetry Collector** provides a vendor-agnostic pipeline to receive, process, and export telemetry, reducing the need to operate multiple agents. citeturn2search3  
- **Prometheus** scrapes metrics endpoints and stores time series, enabling alerting via rules over collected data. citeturn3search4turn3search0  
- **Grafana** dashboards provide an at-a-glance view using panels querying configured data sources. citeturn3search1turn3search16  

Model serving:
- vLLM provides an HTTP server implementing OpenAI-compatible APIs, which makes it practical to swap internal clients between hosted and self-hosted endpoints with minimal change. citeturn0search3  

Future hardening (recommended, not required now) includes RBAC separation, namespace network policies, egress deny-by-default, private registry, and retention policies; the downloadable file keeps these optional because you explicitly stated security constraints are relaxed at present.

