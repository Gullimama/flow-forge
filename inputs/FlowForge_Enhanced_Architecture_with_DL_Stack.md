# FlowForge — Enhanced Architecture with Deep Learning Stack

## Preamble: Analysis of Requirements and Existing Solution

### Requirements analysis

FlowForge must solve a **specific, bounded, high-value problem**: produce a single evidence-backed research document (`system-flows-research.md`) that maps the true runtime and static behaviour of a large Java 11 / Micronaut reactive microservice estate. The requirements have several properties that shape the deep learning strategy:

| Requirement property | Implication for DL stack |
|---|---|
| Research-only, no code generation | Models need to **understand and summarize**, not produce runnable code |
| Large Java 11 + Micronaut codebase | Need **code-aware** embeddings, not generic text embeddings |
| Two log formats (app + Istio) | Need **log-specialized** parsing and anomaly detection |
| Graph-first flow reconstruction | Need **graph-native** reasoning, not just flat retrieval |
| Evidence-backed confidence scores | Every DL output must be **grounded** and traceable |
| Bulk batch processing, not streaming | Can afford **higher-quality** models; latency is secondary to accuracy |
| Prepares for Java 25 + Spring + Dapr rewrite | Need **migration-aware** classification and pattern matching |
| 60+ microservices, cross-service flows | Need **multi-hop** reasoning across service boundaries |

### Implementation technology: Java 25 + Spring AI

FlowForge itself is implemented in **Java 25** using the **Spring Boot 4.0+** ecosystem and **Spring AI** for all model interactions. This choice provides:

| Choice | Rationale |
|---|---|
| **Java 25** | Virtual threads (Project Loom) for massive concurrency without async complexity; record types for clean data modelling; pattern matching and sealed interfaces for type safety; structured concurrency for parallel task management |
| **Spring Boot 4.0+** | Mature ecosystem, auto-configuration, health checks, metrics via Micrometer, native GraalVM compilation path |
| **Spring AI** | Unified abstraction over LLM chat models (vLLM via OpenAI-compatible API), embedding models (TEI), vector stores (Qdrant), structured output converters, and prompt templates |
| **Spring Data** | JPA for PostgreSQL, Neo4j driver for graph, OpenSearch client for search — all with repository abstractions |
| **Gradle multi-module** | Clean separation of services and shared libraries, version catalogs for dependency management |
| **Testcontainers** | Integration tests with real PostgreSQL, Neo4j, MinIO, OpenSearch, Qdrant — no mocks needed |

### Existing solution analysis — strengths

The original architecture (§1–§20) is fundamentally sound:

1. **Four-store model** (MinIO / OpenSearch / Qdrant / Neo4j + PostgreSQL metadata) correctly separates concerns — raw evidence, exact search, semantic retrieval, and relationship truth.
2. **Graph-first principle** is right. Vector similarity alone cannot reconstruct call chains; graph traversal can.
3. **API-triggered, batch-oriented** design avoids unnecessary complexity of streaming or continuous sync.
4. **Static + runtime fusion** is the correct epistemic model — source code is intent, logs are reality, truth requires both.
5. **Orchestration split** (Dapr for workflow state, Argo for batch DAGs) is practical and avoids single-orchestrator lock-in.
6. **Migration-aware tagging** (§15) future-proofs the research output.

### Existing solution analysis — gaps the DL stack must fill

| Gap | Why it matters |
|---|---|
| **Generic embeddings** instead of code-aware embeddings | Generic text models miss Java/Micronaut semantic structure (annotations, reactive chains, DI patterns) |
| **No code-specific understanding model** | Cannot reliably identify reactive complexity, service boundaries, or idiomatic patterns |
| **No log anomaly detection** | Bulk log corpus needs automated identification of failure signatures, not just search |
| **No graph neural reasoning** | Graph store exists but no DL-powered link prediction or flow completion |
| **No reranking layer** | First-pass retrieval (BM25 + kNN) will produce noisy candidates across 60+ services |
| **No structured generation** | Free-form LLM output will drift from the strict flow template (§14) |
| **No multi-stage synthesis** | Single-pass generation over complex multi-service flows is unreliable |
| **No chunking strategy** | Naive text splitting destroys AST-level code semantics |
| **No model lifecycle discipline** | No versioning, caching, or A/B evaluation framework |

---

## 1. Deep Learning Design Principles for FlowForge

### 1.1 DL is the lens, not the truth source

Deep learning components **discover, rank, cluster, and summarize**. They never override deterministic evidence from parsers, graph traversals, or exact log queries. Every DL-produced claim must carry an evidence pointer back to a deterministic artifact.

### 1.2 Code-aware over generic

Every model that touches source code must be trained on (or fine-tuned for) code, not just natural language. Java/Micronaut idioms — annotation-driven DI, reactive chains (`Mono`/`Flux`/`Flowable`), HTTP client declarations — are invisible to generic NLP models.

### 1.3 Batch quality over interactive speed

FlowForge is a batch research platform. A research run can take hours. Therefore, prefer **higher-accuracy models** with longer inference times over faster-but-weaker models. Latency SLOs are measured in minutes per flow, not milliseconds per token.

### 1.4 Modular and replaceable

Every DL component must be behind a clean Spring interface (strategy pattern) so that models can be swapped, upgraded, or disabled without changing the pipeline. The system must produce valid (if lower-quality) output with DL components disabled.

### 1.5 GPU-budget-aware tiering

| Tier | GPU budget | Embedding model | Generation model | Reranker | GNN |
|---|---|---|---|---|---|
| **Minimal** | 1× A100 40GB | UniXcoder-base (125M) | Qwen2.5-Coder-7B-Instruct | None (skip) | None (skip) |
| **Recommended** | 2× A100 80GB | CodeSage-large (1.3B) | Qwen2.5-Coder-32B-Instruct | bge-reranker-v2-m3 | Custom GCN |
| **Maximum** | 4× A100 80GB or 4× H100 | CodeSage-large + fine-tuned | DeepSeek-Coder-V2-Instruct-236B (MoE) | Cross-encoder ensemble | GAT + GCN |

---

## 2. Enhanced Architecture — Complete DL Stack

### 2.0 Updated high-level architecture diagram

```text
                                  +──────────────────────────+
                                  │    FlowForge API         │
                                  │  Spring Boot REST API    │
                                  +────────────┬─────────────+
                                               │
          ┌────────────────────────────────────┤────────────────────────────────────┐
          │                                    │                                    │
          ▼                                    │                                    ▼
┌─────────────────────────┐                    │                     ┌──────────────────────────────┐
│ GitHub Snapshot Ingest   │                    │                     │ Azure Blob Log Ingest         │
│ master clone/delta       │                    │                     │ list → download → extract     │
└────────────┬────────────┘                    │                     └─────────────┬────────────────┘
             │                                 │                                   │
             ▼                                 │                                   ▼
                      ┌────────────────────────┴───────────────────────────┐
                      │              Raw Evidence Store (MinIO)             │
                      └────────────────────────┬───────────────────────────┘
                                               │
         ┌─────────────────────────────────────┼──────────────────────────────────────┐
         │                                     │                                      │
         ▼                                     ▼                                      ▼
┌─────────────────────┐         ┌──────────────────────────┐          ┌────────────────────────────┐
│ Static Code Parser   │         │ Runtime Log Parser        │          │ Topology Enrichment         │
│ JavaParser (native)  │         │ App + Istio normalizer    │          │ K8s / Helm / Istio          │
│ AST-aware chunker    │         │ Log anomaly detector      │          │ (Fabric8 client)            │
└──────────┬──────────┘         └────────────┬─────────────┘          └────────────┬───────────────┘
           │                                  │                                     │
           ▼                                  ▼                                     ▼
  ┌────────────────────────────────────────────────────────────────────────────────────────────┐
  │                          Knowledge Store Fanout Layer                                       │
  │                                                                                            │
  │   ┌──────────────┐    ┌───────────────────┐    ┌──────────────┐    ┌────────────────────┐  │
  │   │  OpenSearch   │    │  Qdrant            │    │  Neo4j       │    │  PostgreSQL        │  │
  │   │  exact search │    │  code embeddings   │    │  flow graph  │    │  metadata/runs     │  │
  │   │  log analytics│    │  log embeddings    │    │  GNN-enhanced│    │  checkpoints       │  │
  │   │              │    │  flow embeddings   │    │  link predict │    │                    │  │
  │   └──────┬───────┘    └─────────┬─────────┘    └──────┬───────┘    └────────────────────┘  │
  └──────────┼───────────────────────┼────────────────────┼────────────────────────────────────┘
             │                       │                     │
             ▼                       ▼                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                    Hybrid GraphRAG Retrieval Layer                    │
  │                     (Spring AI VectorStore + custom)                 │
  │  ┌────────────┐   ┌───────────────┐   ┌──────────────────────────┐  │
  │  │ BM25 Lexical│   │ kNN Semantic   │   │ Graph Neighbourhood     │  │
  │  │ (OpenSearch)│   │ (Qdrant)       │   │ Expansion (Neo4j)       │  │
  │  └──────┬─────┘   └───────┬───────┘   └────────────┬─────────────┘  │
  │         └──────────┬───────┘                         │                │
  │                    ▼                                 │                │
  │         ┌──────────────────┐                         │                │
  │         │ Cross-Encoder     │◄────────────────────────┘                │
  │         │ Reranker (TEI)   │                                          │
  │         └────────┬─────────┘                                          │
  └──────────────────┼────────────────────────────────────────────────────┘
                     │
                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │              Multi-Stage Research Synthesis Engine                    │
  │                  (Spring AI ChatModel + structured output)          │
  │                                                                      │
  │  Stage 1: Flow Skeleton           — graph-driven, deterministic     │
  │  Stage 2: Evidence Attachment     — retrieval-augmented              │
  │  Stage 3: Narrative Synthesis     — LLM with structured output      │
  │  Stage 4: Confidence Scoring      — ensemble deterministic + DL     │
  │  Stage 5: Migration Annotation    — classifier + rule engine        │
  │  Stage 6: Consistency Validation  — cross-flow contradiction check  │
  └──────────────────┬───────────────────────────────────────────────────┘
                     │
                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                   system-flows-research.md                           │
  │                   + flow-catalog.json + service-catalog.json         │
  │                   + dependency-graph.json + runtime-edge-summary.json│
  │                   + research-run-manifest.json                       │
  └──────────────────────────────────────────────────────────────────────┘
```

---

## 3. DL Component 1 — Code-Aware Embedding Model

### AST-aware chunking strategy (critical)

Use **JavaParser** (native Java AST library — superior to Tree-sitter for Java) to produce AST-aligned chunks:

```
Chunking rules:
1. Each public class → one chunk (with class-level annotations and imports as preamble)
2. Each method → one chunk (with enclosing class context as prefix)
3. Each endpoint definition → one chunk (controller method + annotations + route)
4. Each client interface → one chunk (Micronaut @Client + all methods)
5. Each config block → one chunk (with property path context)
6. Maximum chunk size: 1500 tokens (with 200-token overlap at method boundary)
7. Metadata attached: service_id, module, file_path, class_fqn, method_name, snapshot_id
```

### Embedding pipeline

```text
Source file → JavaParser AST → AST-aware chunker → Chunk + metadata
    → Spring AI EmbeddingModel (TEI CodeSage-large)
    → Spring AI VectorStore (Qdrant upsert)
```

---

## 4–9. DL Components 2–7

_(Log anomaly detection via Smile, GNN via DJL, Cross-encoder reranker via TEI, Generation via vLLM+Spring AI ChatModel, 6-stage synthesis pipeline, Migration pattern classifier via DJL — see detailed stage guides)_

---

## 10. Graceful DL Degradation

| DL Component | Degraded fallback | Impact |
|---|---|---|
| Code embedding model offline | Skip Qdrant; use OpenSearch BM25 only | Lower recall |
| Log embedding model offline | Skip Qdrant; use OpenSearch BM25 only | Lower recall |
| Reranker offline | RRF score fusion (no reranking) | Noisier evidence |
| Generation model offline | Template output with evidence pointers only | Not human-friendly |
| GNN offline | Use only observed graph edges | Potentially incomplete flows |
| Migration classifier offline | Rule engine only | Misses nuanced cases |
| Log anomaly detector offline | Skip anomaly episodes | No failure flow discovery |

---

## 11. Updated Final Recommended Stack

### Platform & Runtime
- **Java 25** + **Spring Boot 4.0+** + **Spring AI**
- AKS, Dapr (service invocation + pub/sub), Argo Workflows (batch DAGs)
- Gradle multi-module, Testcontainers, JUnit 5

### Data Stores
- MinIO, OpenSearch, Qdrant (Spring AI VectorStore), Neo4j (Spring Data), PostgreSQL (Spring Data JPA), Redis (optional)

### Parsing
- **JavaParser** (AST), SnakeYAML/Jackson YAML, Fabric8 K8s client, Java Drain implementation, **SPMF** (PrefixSpan)

### DL Stack
| Layer | Model/Tool | Java Integration |
|---|---|---|
| Code embeddings | CodeSage-large via TEI | Spring AI EmbeddingModel |
| Log embeddings | E5-large-v2 via TEI | Spring AI EmbeddingModel |
| Reranking | bge-reranker-v2-m3 via TEI | Java HTTP client |
| Generation | Qwen2.5-Coder-32B via vLLM | Spring AI ChatModel (OpenAI-compat) |
| Graph reasoning | Custom GAT/GCN | DJL + ONNX Runtime |
| Migration classifier | Fine-tuned heads | DJL + ONNX Runtime |
| Anomaly detection | Smile Isolation Forest | Java-native |
| Sequence mining | SPMF PrefixSpan | Java-native |

### Observability
- Micrometer + Prometheus + Grafana + OpenTelemetry Java Agent + MLflow (Java client)

---

## 12. AKS Deployment & ArgoCD GitOps Strategy

FlowForge runs entirely on **Azure Kubernetes Service (AKS)**. All infrastructure and application components are deployed via **ArgoCD** using a GitOps App-of-Apps pattern. There is no Docker Compose in production or staging — `docker-compose.yml` exists only as an optional local developer convenience.

### 12.1 AKS Cluster Architecture

#### Node Pools

| Node Pool | VM SKU | Min / Max Nodes | Purpose | Taints |
|---|---|---|---|---|
| `system` | Standard_D4s_v5 | 2 / 4 | AKS system pods, ArgoCD, Dapr control plane | `CriticalAddonsOnly=true:NoSchedule` |
| `cpupool` | Standard_D8s_v5 | 3 / 10 | FlowForge API, data stores, CPU pipeline stages | — |
| `gpupool` | Standard_NC24ads_A100_v4 | 0 / 4 | TEI embedding servers, vLLM, GNN inference | `nvidia.com/gpu=present:NoSchedule` |

> GPU node pool uses **cluster autoscaler** with scale-to-zero. Nodes spin up when GPU workloads are scheduled and scale down after idle timeout (15 min).

#### Namespaces

| Namespace | Contents |
|---|---|
| `flowforge` | FlowForge API, pipeline runner pods, Argo Workflow executions |
| `flowforge-infra` | PostgreSQL, MinIO, OpenSearch, Neo4j, Qdrant, Redis |
| `flowforge-ml` | TEI instances (code, log, reranker), vLLM |
| `flowforge-obs` | Prometheus, Grafana, Tempo, MLflow |
| `argo` | Argo Workflows controller and server |
| `dapr-system` | Dapr control plane (operator, sidecar injector, placement, sentry) |
| `argocd` | ArgoCD controller, server, repo-server, applicationset-controller |

### 12.2 ArgoCD App-of-Apps Pattern

A single root `Application` manages all child applications declaratively from Git. Each child application targets a specific component with its own sync policy, health checks, and Helm values or Kustomize overlays.

```text
argocd/app-of-apps.yaml (root Application)
    │
    ├── argocd/apps/postgresql.yaml          → Helm: bitnami/postgresql
    ├── argocd/apps/minio.yaml               → Helm: bitnami/minio
    ├── argocd/apps/opensearch.yaml          → Helm: opensearch/opensearch
    ├── argocd/apps/neo4j.yaml               → Helm: neo4j/neo4j
    ├── argocd/apps/qdrant.yaml              → Helm: qdrant/qdrant
    ├── argocd/apps/redis.yaml               → Helm: bitnami/redis
    │
    ├── argocd/apps/tei-code.yaml            → Kustomize: k8s/ml-serving/tei-code/
    ├── argocd/apps/tei-log.yaml             → Kustomize: k8s/ml-serving/tei-log/
    ├── argocd/apps/tei-reranker.yaml        → Kustomize: k8s/ml-serving/tei-reranker/
    ├── argocd/apps/vllm.yaml                → Kustomize: k8s/ml-serving/vllm/
    │
    ├── argocd/apps/argo-workflows.yaml      → Helm: argo/argo-workflows
    ├── argocd/apps/dapr.yaml                → Helm: dapr/dapr
    │
    ├── argocd/apps/kube-prometheus-stack.yaml → Helm: prometheus-community/kube-prometheus-stack
    ├── argocd/apps/tempo.yaml               → Helm: grafana/tempo
    ├── argocd/apps/mlflow.yaml              → Kustomize: k8s/observability/mlflow/
    │
    ├── argocd/apps/flowforge-api.yaml       → Kustomize: k8s/app/flowforge-api/
    └── argocd/apps/flowforge-dapr.yaml      → Kustomize: k8s/dapr/
```

#### ArgoCD Project

```yaml
# k8s/argocd/project.yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: flowforge
  namespace: argocd
spec:
  description: FlowForge research platform
  sourceRepos:
    - 'https://github.com/tesco/flow-forge.git'
    - 'https://charts.bitnami.com/bitnami'
    - 'https://opensearch-project.github.io/helm-charts'
    - 'https://helm.neo4j.com/neo4j'
    - 'https://qdrant.github.io/qdrant-helm'
    - 'https://argoproj.github.io/argo-helm'
    - 'https://dapr.github.io/helm-charts'
    - 'https://prometheus-community.github.io/helm-charts'
    - 'https://grafana.github.io/helm-charts'
  destinations:
    - namespace: 'flowforge*'
      server: https://kubernetes.default.svc
    - namespace: 'argo'
      server: https://kubernetes.default.svc
    - namespace: 'dapr-system'
      server: https://kubernetes.default.svc
    - namespace: 'flowforge-obs'
      server: https://kubernetes.default.svc
  clusterResourceWhitelist:
    - group: ''
      kind: Namespace
    - group: rbac.authorization.k8s.io
      kind: ClusterRole
    - group: rbac.authorization.k8s.io
      kind: ClusterRoleBinding
  namespaceResourceWhitelist:
    - group: '*'
      kind: '*'
```

#### Root App-of-Apps

```yaml
# k8s/argocd/app-of-apps.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-root
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  source:
    repoURL: https://github.com/tesco/flow-forge.git
    targetRevision: HEAD
    path: k8s/argocd/apps
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - PrunePropagationPolicy=foreground
```

### 12.3 Infrastructure Deployment via Helm

Each data store is deployed as an ArgoCD Application pointing to its upstream Helm chart with environment-specific values overrides stored in Git.

#### Example: PostgreSQL ArgoCD Application

```yaml
# k8s/argocd/apps/postgresql.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-postgresql
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "1"
  labels:
    app.kubernetes.io/part-of: flowforge
    flowforge/tier: infrastructure
spec:
  project: flowforge
  sources:
    - repoURL: https://charts.bitnami.com/bitnami
      chart: postgresql
      targetRevision: 16.4.*
      helm:
        releaseName: flowforge-pg
        valueFiles:
          - $values/k8s/infrastructure/postgresql/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: HEAD
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-infra
  syncPolicy:
    automated:
      prune: false
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

#### Infrastructure Helm Values Summary

| Component | Helm Chart | Namespace | Key Values |
|---|---|---|---|
| PostgreSQL 16 | bitnami/postgresql 16.4.x | flowforge-infra | 2 CPU / 4Gi, 50Gi PVC, Flyway init-db |
| MinIO | bitnami/minio 14.x | flowforge-infra | 4 CPU / 8Gi, 100Gi PVC, 8 default buckets |
| OpenSearch 2.18 | opensearch/opensearch 2.x | flowforge-infra | 3 replicas, 8Gi heap, 100Gi PVC, security plugin disabled for internal |
| Neo4j 5.25 | neo4j/neo4j 5.x | flowforge-infra | Community edition, 4Gi heap, 50Gi PVC, APOC plugin |
| Qdrant 1.12 | qdrant/qdrant 0.x | flowforge-infra | 4Gi memory, 50Gi PVC, gRPC + REST |
| Redis 7 | bitnami/redis 19.x | flowforge-infra | 1Gi, no persistence (Dapr state cache only) |

### 12.4 ML Model Serving on GPU Nodes

TEI and vLLM are deployed as plain Kubernetes Deployments (no Helm charts) managed via Kustomize. Each deployment includes GPU resource requests, node affinity for the GPU pool, and tolerations for the GPU taint.

```yaml
# k8s/ml-serving/vllm/deployment.yaml (pattern)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vllm
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: vllm
    app.kubernetes.io/part-of: flowforge
    flowforge/tier: ml-serving
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: vllm
  template:
    metadata:
      labels:
        app.kubernetes.io/name: vllm
    spec:
      nodeSelector:
        agentpool: gpupool
      tolerations:
        - key: nvidia.com/gpu
          operator: Exists
          effect: NoSchedule
      containers:
        - name: vllm
          image: vllm/vllm-openai:v0.6.6
          args:
            - --model
            - Qwen/Qwen2.5-Coder-32B-Instruct
            - --tensor-parallel-size
            - "2"
            - --max-model-len
            - "32768"
            - --gpu-memory-utilization
            - "0.90"
            - --port
            - "8000"
          ports:
            - containerPort: 8000
              name: http
          resources:
            requests:
              cpu: "4"
              memory: 32Gi
              nvidia.com/gpu: "2"
            limits:
              nvidia.com/gpu: "2"
          readinessProbe:
            httpGet:
              path: /v1/models
              port: 8000
            initialDelaySeconds: 120
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 180
            periodSeconds: 30
          volumeMounts:
            - name: model-cache
              mountPath: /root/.cache/huggingface
      volumes:
        - name: model-cache
          persistentVolumeClaim:
            claimName: vllm-model-cache
```

#### ML Serving Resource Summary

| Component | GPU | CPU | Memory | PVC (model cache) |
|---|---|---|---|---|
| TEI CodeSage-large | 1× A100 | 2 | 16Gi | 20Gi |
| TEI E5-large-v2 | 1× A100 | 2 | 16Gi | 20Gi |
| TEI bge-reranker-v2-m3 | 1× A100 | 2 | 16Gi | 20Gi |
| vLLM Qwen2.5-Coder-32B | 2× A100 | 4 | 32Gi | 100Gi |

### 12.5 Application Deployment

FlowForge application services (API, pipeline runner) are deployed as Kubernetes Deployments with Dapr sidecar annotations, ConfigMaps for `application.yml`, and Secrets from Azure Key Vault via Dapr secret store.

```yaml
# k8s/app/flowforge-api/deployment.yaml (pattern)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: flowforge-api
  namespace: flowforge
  labels:
    app.kubernetes.io/name: flowforge-api
    app.kubernetes.io/part-of: flowforge
spec:
  replicas: 2
  selector:
    matchLabels:
      app.kubernetes.io/name: flowforge-api
  template:
    metadata:
      labels:
        app.kubernetes.io/name: flowforge-api
      annotations:
        dapr.io/enabled: "true"
        dapr.io/app-id: "flowforge-api"
        dapr.io/app-port: "8080"
        dapr.io/log-level: "info"
    spec:
      nodeSelector:
        agentpool: cpupool
      serviceAccountName: flowforge-api
      containers:
        - name: api
          image: flowforgeacr.azurecr.io/flowforge-api:latest
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "aks"
            - name: JAVA_TOOL_OPTIONS
              value: >-
                -javaagent:/opt/opentelemetry-javaagent.jar
                -Dotel.service.name=flowforge-api
                -Dotel.exporter.otlp.endpoint=http://tempo.flowforge-obs.svc.cluster.local:4318
          envFrom:
            - configMapRef:
                name: flowforge-api-config
            - secretRef:
                name: flowforge-api-secrets
          resources:
            requests:
              cpu: "2"
              memory: 4Gi
            limits:
              cpu: "4"
              memory: 8Gi
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 30
```

### 12.6 Secret Management

Secrets follow a layered approach — no secrets are stored in Git:

| Secret Type | Mechanism | Example |
|---|---|---|
| Database credentials | Azure Key Vault → Dapr Secret Store → env injection | `FLOWFORGE_POSTGRES_PASSWORD` |
| ACR pull credentials | AKS managed identity (ACR integration) | Kubelet identity |
| GitHub PAT | Azure Key Vault → Dapr Secret Store | `FLOWFORGE_GITHUB_TOKEN` |
| MinIO credentials | Azure Key Vault → Sealed Secret or External Secrets Operator | `MINIO_ROOT_PASSWORD` |
| Model HuggingFace token | Azure Key Vault → K8s Secret (External Secrets Operator) | `HF_TOKEN` |

### 12.7 k8s/ Directory Structure

```text
k8s/
├── argocd/
│   ├── project.yaml                        # ArgoCD AppProject
│   ├── app-of-apps.yaml                    # Root Application
│   └── apps/                               # One Application YAML per component
│       ├── postgresql.yaml
│       ├── minio.yaml
│       ├── opensearch.yaml
│       ├── neo4j.yaml
│       ├── qdrant.yaml
│       ├── redis.yaml
│       ├── tei-code.yaml
│       ├── tei-log.yaml
│       ├── tei-reranker.yaml
│       ├── vllm.yaml
│       ├── argo-workflows.yaml
│       ├── dapr.yaml
│       ├── kube-prometheus-stack.yaml
│       ├── tempo.yaml
│       ├── mlflow.yaml
│       ├── flowforge-api.yaml
│       └── flowforge-dapr.yaml
├── infrastructure/
│   ├── postgresql/
│   │   └── values.yaml                     # Bitnami Helm values
│   ├── minio/
│   │   └── values.yaml
│   ├── opensearch/
│   │   └── values.yaml
│   ├── neo4j/
│   │   └── values.yaml
│   ├── qdrant/
│   │   └── values.yaml
│   └── redis/
│       └── values.yaml
├── ml-serving/
│   ├── tei-code/
│   │   ├── kustomization.yaml
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── pvc.yaml
│   ├── tei-log/
│   │   ├── kustomization.yaml
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── pvc.yaml
│   ├── tei-reranker/
│   │   ├── kustomization.yaml
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── pvc.yaml
│   └── vllm/
│       ├── kustomization.yaml
│       ├── deployment.yaml
│       ├── service.yaml
│       └── pvc.yaml
├── app/
│   └── flowforge-api/
│       ├── kustomization.yaml
│       ├── deployment.yaml
│       ├── service.yaml
│       ├── ingress.yaml
│       ├── configmap.yaml
│       ├── serviceaccount.yaml
│       └── hpa.yaml
├── observability/
│   ├── kube-prometheus-stack/
│   │   └── values.yaml
│   ├── tempo/
│   │   └── values.yaml
│   └── mlflow/
│       ├── kustomization.yaml
│       ├── deployment.yaml
│       ├── service.yaml
│       └── pvc.yaml
├── argo/                                   # Argo Workflow templates
│   ├── flowforge-pipeline.yaml
│   └── workflow-rbac.yaml
├── dapr/                                   # Dapr component definitions
│   ├── pubsub.yaml
│   ├── statestore.yaml
│   └── secretstore.yaml
├── grafana/                                # Grafana dashboard JSON
│   └── flowforge-dashboard.json
└── monitoring/                             # PrometheusRule alerts
    └── flowforge-alerts.yaml
```

### 12.8 Sync Waves and Ordering

ArgoCD sync waves ensure components start in dependency order:

| Wave | Components | Rationale |
|---|---|---|
| 0 | Namespaces | Must exist before any resources |
| 1 | PostgreSQL, MinIO, Redis | Core data stores — no dependencies |
| 2 | OpenSearch, Neo4j, Qdrant | Secondary stores — can start in parallel with wave 1 |
| 3 | Dapr components, Argo Workflows | Depend on Redis (Dapr state) |
| 4 | TEI instances (code, log, reranker) | GPU pods — depend on stores being ready |
| 5 | vLLM | Largest GPU allocation — last ML component |
| 6 | MLflow, Prometheus, Grafana, Tempo | Observability stack |
| 7 | FlowForge API | Application — depends on all infrastructure |

Sync waves are applied via annotation: `argocd.argoproj.io/sync-wave: "1"`.

### 12.9 Environment Promotion Strategy

```text
Git branching:
  main ──────────────────────────────────────────────────► (prod ArgoCD watches)
    └─ develop ──────────────────────────────────────────► (staging ArgoCD watches)
         └─ feature/* ──► PR ──► merge to develop

k8s/ overlays (Kustomize):
  k8s/base/           ← shared manifests
  k8s/overlays/
    ├── dev/           ← CPU-only, single-replica, minimal resources
    ├── staging/       ← GPU nodes, reduced replicas, full stack
    └── prod/          ← Full resources, autoscaling, HA
```

Each environment has its own ArgoCD `Application` pointing to the respective branch and overlay. Promotions happen via Git merges — ArgoCD auto-syncs on push.

---

## 13. Final One-Sentence Architecture Summary

**FlowForge** is an AKS-hosted internal research platform, built with **Java 25 and Spring AI**, that pulls on-demand master snapshots from private GitHub, ingests all available Splunk log zip files from Azure Blob Storage, fuses static code knowledge with runtime evidence through code-aware embeddings, graph neural reasoning, hybrid GraphRAG retrieval, and a multi-stage LLM synthesis pipeline into a single condensed, evidence-grounded markdown document describing the true system flows — with every claim traceable, every flow confidence-scored, and every service pre-classified for modernization into Spring Boot, Dapr, and virtual-thread-based execution.
