# Codebase vs Development Guide — Stage 20 Implementation Status

**Evaluation date:** 2026-03-07  
**Scope:** Stages 1–20 of the FlowForge development guide  
**Reference:** `development-guide/stage-01-project-scaffolding.md` through `stage-20-vllm-generation-model.md`

---

## Executive summary

The codebase **largely matches** the development guide through Stage 20. All 20 stage modules exist, K8s/ArgoCD manifests are present, and the verification logs (`logs/stage-19.log`, `logs/stage-20.log`) report **PASS** for stages 1–19 and 1–20 respectively. A few **gaps and variances** remain; the only one that can cause a runtime failure in normal use is the **missing `researchPipeline` bean** when dispatch mode is `in-process` and a RESEARCH job is triggered.

| Category              | Status |
|-----------------------|--------|
| Stages 1–20 modules   | ✅ All present and building |
| Stage 20 verification | ✅ PASS (per `logs/stage-20.log`) |
| Critical gaps         | 1 (researchPipeline bean) |
| Minor variances       | 2 (migration location, LLM response API) |

---

## Per-stage implementation status

### Stages 1–4 (Foundation, PostgreSQL, MinIO, Control plane API)

| Stage | Guide deliverables | Codebase status |
|-------|--------------------|-----------------|
| **01** | Monorepo, `FlowForgeProperties`, shared models, Docker base, ArgoCD bootstrap, Makefile | ✅ `settings.gradle.kts`, `build.gradle.kts`, `libs/common`, `k8s/argocd/`, version catalog |
| **02** | PostgreSQL ArgoCD, Flyway V1 schema, JPA entities, repositories, `MetadataService` | ✅ Schema in `libs/common/src/main/resources/db/migration/V1__initial_schema.sql`; entities/repos/service in `libs/common`. **Variance:** Guide lists migration under `services/api`; implementation uses `libs/common` (shared schema). |
| **03** | MinIO ArgoCD, buckets, `MinioStorageClient`, `MinioHealthIndicator`, key conventions | ✅ `libs/common/client/MinioStorageClient.java`, `MinioObjectInfo`, `MinioHealthIndicator`, `k8s/argocd/apps/minio.yaml`, `k8s/infrastructure/minio/values.yaml` |
| **04** | Spring Boot API, snapshot/log/research/job controllers, `JobDispatcher` (Stub + InProcess), request/response DTOs, K8s deployment | ✅ All controllers, `StubJobDispatcher`, `InProcessJobDispatcher`, `SnapshotWorkerConfig` (bean `snapshotWorker`), `BlobIngestionWorkerConfig` (bean `blobIngestionWorker`). **Gap:** No `researchPipeline` bean — RESEARCH dispatch will throw `NoSuchBeanDefinitionException` when `flowforge.dispatch.mode=in-process`. |

### Stages 5–8 (Ingestion, OpenSearch, Code parser)

| Stage | Guide deliverables | Codebase status |
|-------|--------------------|-----------------|
| **05** | `GitHubSnapshotClient`, `FileClassifier`, `GitHubSnapshotWorker`, SnapshotResult | ✅ In `libs/ingest`: all present. Worker is `@ConditionalOnProperty(flowforge.minio.enabled)` (guide does not specify; reasonable). |
| **06** | `AzureBlobClient`, `ZipExtractor`, `BlobIngestionWorker`, full/incremental, safety checks | ✅ In `libs/ingest`: all present. Worker `@ConditionalOnBean(AzureBlobClient)`. `BlobRecordRepository.existsByEtag` present. |
| **07** | OpenSearch ArgoCD, 4 indexes (code-artifacts, config-artifacts, runtime-events, anomaly-episodes), `OpenSearchClientWrapper`, `OpenSearchIndexInitializer`, Java analyzer | ✅ `libs/common`: `OpenSearchClientWrapper`, `OpenSearchHealthIndicator`, `OpenSearchIndexInitializer`; `opensearch/*.json` for all four indexes; `k8s/argocd/apps/opensearch.yaml` |
| **08** | Code parser (JavaParser), chunker, Micronaut/Reactive analysis | ✅ `libs/code-parser`: `CodeParsingService`, `AstAwareChunker`, `MicronautAnnotationRecognizer`, `ReactiveChainAnalyzer`, etc. |

### Stages 9–13 (Log parser, Topology, Neo4j, Anomaly, Pattern mining)

| Stage | Guide deliverables | Codebase status |
|-------|--------------------|-----------------|
| **09** | Log parser, `ParsedLogEvent`, trace context | ✅ `libs/log-parser`: `ParsedLogEvent`, `LogParsingService`, `TraceContextExtractor` |
| **10** | Topology enrichment, call graph, K8s/Istio/Helm parsing | ✅ `libs/topology`: `TopologyEnrichmentService`, `TopologyEdge`, manifest parsers |
| **11** | Neo4j graph, services/relationships | ✅ `libs/graph`: Neo4j integration; `k8s/argocd/apps/neo4j.yaml` |
| **12** | Anomaly detection, episodes | ✅ `libs/anomaly`: `AnomalyDetectionService`, `AnomalyEpisodeBuilder`, `LogFeatureEngineer` |
| **13** | SPMF PrefixSpan, `CallSequenceExtractor`, `SequencePatternMiner`, `PatternAnalyzer`, `PatternMiningService` | ✅ `libs/pattern-mining`: extractor, miner, analyzer, service; evidence in MinIO |

### Stages 14–18 (Vector store, Embeddings, Reranker, Retrieval)

| Stage | Guide deliverables | Codebase status |
|-------|--------------------|-----------------|
| **14** | Qdrant, collections, vector store client | ✅ `libs/vector-store`: `VectorStoreService`, `QdrantHealthIndicator`, `QdrantCollectionInitializer`; `k8s/argocd/apps/qdrant.yaml` |
| **15** | Code embedding pipeline (TEI code) | ✅ `libs/embedding`: `CodeEmbeddingService`; TEI code deployment in `k8s/ml-serving/tei-code/` |
| **16** | Log embedding pipeline (TEI log) | ✅ `libs/embedding`: `LogEmbeddingService`; `k8s/ml-serving/tei-log/` |
| **17** | Cross-encoder reranker (TEI), `CrossEncoderReranker`, `ResilientReranker`, health | ✅ `libs/reranker`: client, resilient wrapper, health indicator; `k8s/ml-serving/tei-reranker/` |
| **18** | Hybrid retrieval (vector + BM25 + graph, RRF, rerank) | ✅ `libs/retrieval`: `HybridRetrievalService`, `BM25Retriever`, etc. |

### Stages 19–20 (Flow builder, vLLM)

| Stage | Guide deliverables | Codebase status |
|-------|--------------------|-----------------|
| **19** | Flow candidate builder, `FlowCandidate`, `FlowCandidateBuilder`, evidence in MinIO | ✅ `libs/flow-builder`: `FlowCandidate`, `FlowStep`, `FlowEvidence`, `FlowCandidateBuilder`; stage-19.log PASS |
| **20** | vLLM deployment, Spring AI `ChatModel`, `PromptTemplateManager`, `StructuredOutputService`, `LlmGenerationService`, BeanOutputConverter, Resilience4j | ✅ `libs/llm`: `LlmConfig` (ChatModel from vLLM baseUrl), `PromptTemplateManager` (11 templates), `StructuredOutputService` (BeanOutputConverter), `LlmGenerationService` (circuit breaker, retry, fallbacks, metrics). `k8s/argocd/apps/vllm.yaml`, `k8s/ml-serving/vllm/` (Qwen2.5-Coder-32B-Instruct, 2 GPUs). **Variance:** Guide uses `getOutput().getContent()`; code uses `getOutput().getText()` (Spring AI API difference; both valid). |

---

## Gaps and variances

### 1. Missing `researchPipeline` bean (Stage 4)

- **Guide:** `InProcessJobDispatcher` calls `applicationContext.getBean("researchPipeline", Runnable.class).run()` for job type `RESEARCH`.
- **Codebase:** No bean named `researchPipeline` is defined. `SnapshotWorkerConfig` and `BlobIngestionWorkerConfig` provide `snapshotWorker` and `blobIngestionWorker` only.
- **Impact:** If `flowforge.dispatch.mode=in-process` and a client calls `POST /api/v1/research/run`, the job is created and dispatch runs; `getBean("researchPipeline", Runnable.class)` throws `NoSuchBeanDefinitionException`.
- **Recommendation:** Add a `researchPipeline` bean (e.g. in a new `ResearchPipelineConfig` or in an existing config): either a stub that no-ops and marks the job completed, or a real pipeline when Stage 21+ is implemented. Until then, use `flowforge.dispatch.mode=stub` (default) to avoid RESEARCH dispatch, or add a stub bean.

### 2. Flyway migration location (Stage 2)

- **Guide:** “Files to create” lists `services/api/src/main/resources/db/migration/V1__initial_schema.sql`.
- **Codebase:** Migration lives in `libs/common/src/main/resources/db/migration/V1__initial_schema.sql`. Flyway is in `libs/common`; API depends on common, so migrations are on the API classpath when it runs.
- **Impact:** None; schema is shared and applied correctly.
- **Recommendation:** Optional: update the guide to allow migration in `libs/common` for shared schema.

### 3. LLM response content API (Stage 20)

- **Guide:** `response.getResult().getOutput().getContent()`.
- **Codebase:** `result.getOutput().getText()` in `LlmGenerationService` and `StructuredOutputService`.
- **Impact:** None if the Spring AI version in use exposes `getText()` on the output object; naming/API may differ by version.
- **Recommendation:** None unless the guide is tied to a specific Spring AI version that only has `getContent()`.

---

## Module and K8s checklist (Stages 1–20)

| Item | Present |
|------|--------|
| `settings.gradle.kts` includes all libs + services | ✅ |
| `libs/common`, `ingest`, `code-parser`, `log-parser`, `topology`, `graph`, `anomaly`, `pattern-mining`, `vector-store`, `embedding`, `reranker`, `retrieval`, `flow-builder`, `llm`, `test-fixtures` | ✅ |
| `services/api`, `services/orchestrator` | ✅ |
| ArgoCD: project, app-of-apps, namespaces, postgresql, minio, opensearch, neo4j, qdrant, tei-code, tei-log, tei-reranker, vllm, flowforge-api | ✅ |
| K8s app: flowforge-api (deployment, service, ingress, hpa, kustomization) | ✅ |
| K8s ml-serving: vllm, tei-code, tei-log, tei-reranker (deployments, services, PVCs where specified) | ✅ |
| Infrastructure values: minio, postgresql, opensearch, neo4j, qdrant | ✅ |

---

## Verification logs

- `logs/stage-19.log`: Stages 1–19 PASS (flow-builder, flow candidates).
- `logs/stage-20.log`: Stages 1–20 PASS (llm, vLLM, prompts, structured output, resilience).

These confirm that build, unit tests, and stage-specific checks for 1–20 are passing as of the last run.

---

## Conclusion

At Stage 20 status, the codebase is **in line with the development guide** for almost all deliverables. The only **corrective action** recommended before relying on in-process RESEARCH jobs is to **define a `researchPipeline` bean** (stub or real). The other variances are documentation or API naming and do not affect behavior.
