# FlowForge Development Guide

> **Java 25 + Spring AI 1.0.3 + Spring Boot 4.0.3**

A 30-stage incremental development guide for building FlowForge — an AKS-hosted research platform that analyzes a Java 11 / Micronaut reactive microservice estate and produces a comprehensive `system-flows-research.md` document.

---

## Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Language** | Java 25 | Virtual threads, records, sealed interfaces, pattern matching, StructuredTaskScope (finalized in JDK 25 via JEP 505) |
| **Framework** | Spring Boot 4.0.3 | Auto-configuration, Actuator, REST, DI |
| **AI Framework** | Spring AI 1.0.3 | ChatModel (vLLM), EmbeddingModel (TEI), VectorStore (Qdrant) |
| **Build** | Gradle (Kotlin DSL) + version catalog | Multi-module, `libs.versions.toml` |
| **Code Parsing** | JavaParser 3.26.3 | Native Java AST parsing, Micronaut-aware |
| **ML** | Smile 3.1.1 | Isolation Forest anomaly detection |
| **Pattern Mining** | SPMF 2.60 | PrefixSpan sequential pattern mining |
| **Deep Learning** | DJL 0.30.0 + ONNX Runtime | GNN inference, migration classification |
| **Graph DB** | Neo4j 5.x (Java Driver 5.26.0) | Service knowledge graph |
| **Vector DB** | Qdrant (Spring AI VectorStore) | Code + log + config embeddings |
| **Search** | OpenSearch 2.x (Java client) | Full-text indexing, BM25 retrieval |
| **Relational DB** | PostgreSQL 16 + Flyway | Metadata, snapshots, audit |
| **Object Store** | MinIO (Java SDK) | Evidence artifacts |
| **Orchestration** | Argo Workflows | DAG pipeline execution on AKS |
| **Service Mesh** | Dapr 1.13 (Java SDK) | Service invocation, pub/sub, state, secrets |
| **Resilience** | Resilience4j 2.2.0 | Circuit breaker, retry, fallback |
| **Template** | FreeMarker 2.3.33 | Document rendering |
| **Metrics** | Micrometer + Prometheus | Pipeline and model metrics |
| **Tracing** | OpenTelemetry Java Agent | Zero-code distributed tracing |
| **Logging** | SLF4J + Logback + Logstash encoder | Structured JSON logging |
| **Experiment Tracking** | MLflow (REST API) | Model training run tracking |

## DL Model Stack (Served Externally)

| Model | Serving | Dimension | Use |
|---|---|---|---|
| CodeSage-large | TEI | 1024 | Code embeddings |
| E5-large-v2 | TEI | 1024 | Log/config embeddings |
| bge-reranker-v2-m3 | TEI | — | Cross-encoder reranking |
| Qwen2.5-Coder-32B-Instruct | vLLM | — | LLM generation (6-stage synthesis) |
| GNN (link pred + node class) | DJL/ONNX | — | Graph structure prediction |

### Development Tiers (GPU-budget-aware)

| Tier | GPU Requirement | Models | Notes |
|---|---|---|---|
| **CPU-only (dev)** | 0 GPUs | all-MiniLM-L6-v2 (384-dim) for embeddings, Qwen2.5-Coder-7B-Instruct (GGUF/llama.cpp) for generation, no reranker, no GNN | For local development and CI. Embedding dimension changes require separate Qdrant collections. Set `flowforge.tier=cpu` profile. |
| **Minimal** | 1× GPU (16GB+) | CodeSage-base via TEI (CPU), Qwen2.5-Coder-7B-Instruct via vLLM (1 GPU), no reranker | Suitable for single-node testing. |
| **Recommended** | 2× A100 80GB | CodeSage-large + E5-large-v2 via TEI (shared GPU), bge-reranker-v2-m3, Qwen2.5-Coder-32B-Instruct (2× A100 tensor parallel) | Production-grade. |

> **Local development:** Use `spring.profiles.active=dev,cpu` to activate CPU-only model configurations. TEI containers support `--dtype float32` for CPU execution (slower but functional). For the LLM, use a local Ollama instance or llama.cpp server as an OpenAI-compatible drop-in.

---

## Stage Index

### Data Foundation (Stages 01–06)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 01 | [Project Scaffolding](stage-01-project-scaffolding.md) | `root`, `libs/common` | Gradle multi-module, version catalog, Java records |
| 02 | [PostgreSQL Metadata](stage-02-postgresql-metadata.md) | `libs/metadata` | Spring Data JPA, Flyway, JPA entities |
| 03 | [MinIO Evidence Store](stage-03-minio-evidence-store.md) | `libs/storage` | MinIO Java SDK, Actuator health |
| 04 | [Control Plane API](stage-04-control-plane-api.md) | `services/api` | Spring Boot REST, virtual threads |
| 05 | [GitHub Snapshot Ingest](stage-05-github-snapshot-ingest.md) | `libs/ingest` | JGit, file classifier |
| 06 | [Blob Ingest + Zip Extract](stage-06-blob-ingest-zip-extract.md) | `libs/ingest` | Azure Storage Blob SDK |

### Parsing & Indexing (Stages 07–11)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 07 | [OpenSearch Setup](stage-07-opensearch-setup.md) | `libs/search` | OpenSearch Java client, 4 index schemas |
| 08 | [Code Parser (JavaParser)](stage-08-code-parser-javaparser.md) | `libs/code-parser` | JavaParser 3.26.3, MicronautCodeVisitor |
| 09 | [Log Parser](stage-09-log-parser.md) | `libs/log-parser` | Pure Java Drain algorithm, multi-format |
| 10 | [Topology Enrichment](stage-10-topology-enrichment.md) | `libs/topology` | Fabric8 7.0.1, sealed interfaces, pattern matching |
| 11 | [Neo4j Knowledge Graph](stage-11-neo4j-knowledge-graph.md) | `libs/graph` | Neo4j Java Driver, Cypher MERGE queries |

### Analytics & ML (Stages 12–13)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 12 | [Log Anomaly Detection](stage-12-log-anomaly-detection.md) | `libs/anomaly` | Smile 3.1.1 IsolationForest |
| 13 | [Sequence Mining](stage-13-sequence-mining.md) | `libs/sequence` | SPMF 2.60 AlgoPrefixSpan |

### Embedding & Vector (Stages 14–17)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 14 | [Qdrant Vector Store](stage-14-qdrant-vector-store.md) | `libs/vectorstore` | Spring AI QdrantVectorStore, 3 collections |
| 15 | [Code Embedding Pipeline](stage-15-code-embedding-pipeline.md) | `libs/embedding` | Spring AI OpenAiEmbeddingModel → TEI CodeSage |
| 16 | [Log Embedding Pipeline](stage-16-log-embedding-pipeline.md) | `libs/embedding` | E5-large-v2 via TEI, stratified sampling |
| 17 | [Cross-Encoder Reranker](stage-17-cross-encoder-reranker.md) | `libs/reranker` | TEI /rerank endpoint, Resilience4j |

### Retrieval & Candidate Building (Stages 18–19)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 18 | [Hybrid GraphRAG Retrieval](stage-18-hybrid-graphrag-retrieval.md) | `libs/retrieval` | StructuredTaskScope, RRF, 5-source fusion |
| 19 | [Flow Candidate Builder](stage-19-flow-candidate-builder.md) | `libs/candidate` | FlowCandidate record, confidence scoring |

### Generation & Synthesis (Stages 20–23)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 20 | [vLLM Generation Model](stage-20-vllm-generation-model.md) | `libs/generation` | Spring AI OpenAiChatModel → vLLM Qwen2.5-Coder |
| 21 | [Synthesis Stages 1–3](stage-21-synthesis-stages-1-3.md) | `libs/synthesis` | FlowAnalysis, CodeExplanation, RiskAssessment |
| 22 | [Synthesis Stages 4–6](stage-22-synthesis-stages-4-6.md) | `libs/synthesis` | DependencyMapping, MigrationPlan, FinalNarrative |
| 23 | [Output Publisher](stage-23-output-publisher.md) | `libs/publisher` | FreeMarker 2.3.33, DocumentAssembler |

### Deep Learning & Classification (Stages 24–25)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 24 | [GNN Module](stage-24-gnn-module.md) | `libs/gnn` | DJL 0.30.0 + ONNX Runtime |
| 25 | [Migration Pattern Classifier](stage-25-migration-classifier.md) | `libs/classifier` | DJL classification heads, feature extraction |

### Operations & Quality (Stages 26–30)

| # | Stage | Module | Key Tech |
|---|---|---|---|
| 26 | [MLflow Experiment Tracking](stage-26-mlflow-tracking.md) | `libs/mlflow` | MLflow REST API, training run tracking |
| 27 | [Evaluation Framework](stage-27-evaluation-framework.md) | `libs/evaluation` | JUnit 5, retrieval/synthesis/embedding metrics |
| 28 | [Argo Workflows Orchestration](stage-28-argo-orchestration.md) | `services/orchestrator` | Argo DAG, PipelineStage interface |
| 29 | [Dapr Integration](stage-29-dapr-integration.md) | `libs/dapr` | Dapr Java SDK 1.13, pub/sub, state, secrets |
| 30 | [Observability](stage-30-observability.md) | `libs/observability` | Micrometer, OTel Java Agent, Grafana |

---

## Project Structure

```
flowforge/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── Dockerfile
├── libs/
│   ├── common/                    # Shared records, config, utilities
│   ├── metadata/                  # PostgreSQL + Flyway (Stage 02)
│   ├── storage/                   # MinIO evidence store (Stage 03)
│   ├── ingest/                    # Git clone + blob ingest (Stages 05-06)
│   ├── search/                    # OpenSearch indexing (Stage 07)
│   ├── code-parser/               # JavaParser AST (Stage 08)
│   ├── log-parser/                # Drain log parser (Stage 09)
│   ├── topology/                  # K8s manifest parsing (Stage 10)
│   ├── graph/                     # Neo4j knowledge graph (Stage 11)
│   ├── anomaly/                   # Smile anomaly detection (Stage 12)
│   ├── sequence/                  # SPMF sequence mining (Stage 13)
│   ├── vectorstore/               # Qdrant vector store (Stage 14)
│   ├── embedding/                 # Code + log embeddings (Stages 15-16)
│   ├── reranker/                  # Cross-encoder reranker (Stage 17)
│   ├── retrieval/                 # Hybrid retrieval (Stage 18)
│   ├── candidate/                 # Flow candidate builder (Stage 19)
│   ├── generation/                # vLLM chat model (Stage 20)
│   ├── synthesis/                 # 6-stage synthesis (Stages 21-22)
│   ├── publisher/                 # FreeMarker output (Stage 23)
│   ├── gnn/                       # DJL GNN inference (Stage 24)
│   ├── classifier/                # Migration classifier (Stage 25)
│   ├── mlflow/                    # MLflow tracking (Stage 26)
│   ├── evaluation/                # Evaluation framework (Stage 27)
│   ├── dapr/                      # Dapr integration (Stage 29)
│   └── observability/             # Metrics + tracing + logging (Stage 30)
├── services/
│   ├── api/                       # REST API (Stage 04)
│   └── orchestrator/              # Argo workflow management (Stage 28)
├── docker/
│   ├── Dockerfile.base            # JDK 25 base image
│   ├── Dockerfile.gpu-base        # GPU base image (CUDA + DJL)
│   └── docker-compose.yml         # LOCAL DEV ONLY
└── k8s/
    ├── argocd/                    # ArgoCD App-of-Apps GitOps
    │   ├── project.yaml           # ArgoCD AppProject
    │   ├── app-of-apps.yaml       # Root Application
    │   └── apps/                  # One Application per component
    ├── infrastructure/            # Helm values for data stores
    │   ├── postgresql/
    │   ├── minio/
    │   ├── opensearch/
    │   ├── neo4j/
    │   ├── qdrant/
    │   └── redis/
    ├── ml-serving/                # K8s manifests for GPU workloads
    │   ├── tei-code/
    │   ├── tei-log/
    │   ├── tei-reranker/
    │   └── vllm/
    ├── app/                       # Application K8s manifests
    │   └── flowforge-api/
    ├── observability/             # Monitoring Helm values
    │   ├── kube-prometheus-stack/
    │   ├── tempo/
    │   └── mlflow/
    ├── argo/                      # Argo Workflow templates
    ├── dapr/                      # Dapr component definitions
    ├── grafana/                   # Dashboard JSON
    └── monitoring/                # PrometheusRule alerts
```

## Key Java 25 Features Used

| Feature | Where Used |
|---|---|
| **Virtual Threads** | All Spring Boot services (`spring.threads.virtual.enabled=true`) |
| **Records** | Domain models, DTOs, configuration properties throughout |
| **Sealed Interfaces** | `TopologyNode`, `MigrationClassification`, `PipelineEvent`, `EvaluationResult` |
| **Pattern Matching (switch)** | Topology node processing, event handling |
| **StructuredTaskScope** | Parallel retrieval (5 sources), parallel embedding |
| **String Templates** (preview) | Log messages, query building |
| **Text Blocks** | Cypher queries, prompt templates, YAML snippets |

> **Note:** Redis is deployed as infrastructure via ArgoCD (see `k8s/argocd/apps/redis.yaml` and `k8s/infrastructure/redis/values.yaml`) but has no dedicated development stage since it requires no application code — it serves only as Dapr's state store and pub/sub backend.

## AKS Deployment (ArgoCD GitOps)

All deployments target **Azure Kubernetes Service** using **ArgoCD** with an App-of-Apps pattern. Docker Compose exists only for local development.

| Tier | Component | Deployment Method | Namespace |
|---|---|---|---|
| **Infrastructure** | PostgreSQL, MinIO, OpenSearch, Neo4j, Qdrant, Redis | Helm via ArgoCD | `flowforge-infra` |
| **ML Serving** | TEI (code, log, reranker), vLLM | Kustomize via ArgoCD (GPU nodes) | `flowforge-ml` |
| **Application** | FlowForge API | Kustomize via ArgoCD | `flowforge` |
| **Pipeline** | Argo Workflow tasks | Argo DAG (CPU + GPU images) | `flowforge` |
| **Orchestration** | Argo Workflows, Dapr | Helm via ArgoCD | `argo`, `dapr-system` |
| **Observability** | Prometheus, Grafana, Tempo, MLflow | Helm/Kustomize via ArgoCD | `flowforge-obs` |

> See architecture document §12 for full details on node pools, sync waves, secret management, and environment promotion strategy.

## How to Use This Guide

1. **Follow stages sequentially** — each depends on prior stages
2. **Each stage** has: Goal → Prerequisites → Code → K8s Deployment → Verification → Files to create
3. **Verification tables** define pass criteria for every stage
4. **Dependencies** use Gradle version catalog (`libs.versions.toml`) consistently
5. **All code** uses Java 25 idioms: records over classes, sealed interfaces over abstract classes, pattern matching over instanceof chains, virtual threads over async
6. **Infrastructure stages** include ArgoCD Application manifests and Helm/Kustomize values
7. **Library stages** include AKS deployment context noting in-cluster DNS names and Argo task resource classes
