# FlowForge Local End-to-End Testing Guide (Docker Desktop)

This guide explains how to stand up the **minimum local stack** with Docker Desktop and run the FlowForge services end‑to‑end on your machine **before** deploying to AKS.

It intentionally avoids Kubernetes, Argo Workflows, and Dapr sidecars; those are covered by the `k8s/**` and `development-guide/stage-2x-*.md` documents. Here you validate that the core pipeline components work together against local containers.

---

## 1. Prerequisites

- **Docker Desktop** (latest, with Docker Compose v2 enabled)
- **JDK** 21+ (or the version used by this repo)
- **Git & Gradle Wrapper** (no global Gradle required)
- A **GitHub Personal Access Token** (for snapshot ingest in Stage 05, if you actually pull real repos)

Check you can build at least once:

```bash
./gradlew clean build -x checkstyle
```

> Note: `-x checkstyle` is used only to bypass style violations during development. Once you are happy with the local flow, fix the remaining Checkstyle warnings and run `./gradlew clean build` to enforce style.

---

## 2. Start local infrastructure with Docker

Create a `docker-compose.yml` in the repo root (or in a `docker/local/` folder) with something like the following minimal stack:

```yaml
version: "3.9"

services:
  postgres:
    image: postgres:16
    container_name: ff-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: flowforge
      POSTGRES_USER: flowforge
      POSTGRES_PASSWORD: flowforge
    ports:
      - "5432:5432"
    volumes:
      - pg_data:/var/lib/postgresql/data

  minio:
    image: minio/minio:RELEASE.2024-09-01T00-00-00Z
    container_name: ff-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minio
      MINIO_ROOT_PASSWORD: minio123
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data

  opensearch:
    image: opensearchproject/opensearch:2.14.0
    container_name: ff-opensearch
    environment:
      discovery.type: single-node
      plugins.security.disabled: "true"
      OPENSEARCH_JAVA_OPTS: "-Xms512m -Xmx512m"
    ports:
      - "9200:9200"

  neo4j:
    image: neo4j:5.22
    container_name: ff-neo4j
    environment:
      NEO4J_AUTH: neo4j/neo4j123
    ports:
      - "7474:7474"
      - "7687:7687"
    volumes:
      - neo4j_data:/data

  qdrant:
    image: qdrant/qdrant:v1.12.0
    container_name: ff-qdrant
    ports:
      - "6333:6333"
    volumes:
      - qdrant_data:/qdrant/storage

  redis:
    image: redis:7
    container_name: ff-redis
    ports:
      - "6379:6379"

  mlflow:
    image: ghcr.io/mlflow/mlflow:latest
    container_name: ff-mlflow
    command: >
      mlflow server
      --host 0.0.0.0
      --port 5000
      --backend-store-uri sqlite:///mlflow.db
      --default-artifact-root /mlruns
    ports:
      - "5000:5000"
    volumes:
      - mlflow_data:/mlruns

volumes:
  pg_data:
  minio_data:
  neo4j_data:
  qdrant_data:
  mlflow_data:
```

Bring everything up:

```bash
docker compose up -d
```

Verify key endpoints:

- Postgres: `localhost:5432`
- MinIO: `http://localhost:9000` (console at `:9001`)
- OpenSearch: `http://localhost:9200`
- Neo4j: `http://localhost:7474`
- Qdrant: `http://localhost:6333`
- Redis: `localhost:6379`
- MLflow: `http://localhost:5000`

---

## 3. Configure FlowForge services for local endpoints

The development guides for each stage describe the Spring configuration properties. For local testing:

- Ensure **`services/api`** and **`services/orchestrator`** use a profile (e.g. `local`) that points to:
  - Postgres at `jdbc:postgresql://localhost:5432/flowforge`
  - MinIO at `http://localhost:9000`
  - OpenSearch at `http://localhost:9200`
  - Neo4j bolt at `bolt://localhost:7687`
  - Qdrant at `http://localhost:6333`
  - Redis at `redis://localhost:6379`
  - MLflow at `http://localhost:5000`

Typical approach:

1. Create `application-local.yml` for `services/api` and `services/orchestrator` if one does not already exist.
2. Mirror the properties from the relevant `development-guide/stage-XX-*.md` documents, but swap service DNS names for `localhost` and the ports from the Docker Compose file.
3. Start each Spring Boot service with:

```bash
./gradlew :services:api:bootRun --args='--spring.profiles.active=local'
./gradlew :services:orchestrator:bootRun --args='--spring.profiles.active=local'
```

With the `local` profile, the API listens on **port 9080** by default (to avoid conflict with other apps on 8080). Override with `FLOWFORGE_API_PORT` if needed. Health: `http://localhost:9080/actuator/health`, liveness: `http://localhost:9080/actuator/health/liveness`.

---

## 4. End-to-end happy-path scenario

Once infra and services are up, you can run an end‑to‑end flow:

1. **Build artifacts** (if you haven’t already):

   ```bash
   ./gradlew clean build -x checkstyle
   ```

2. **Start services** as shown above (`api` and `orchestrator`, plus any additional services you want to run independently).

3. **Trigger GitHub snapshot ingest (Stage 05)**  
   - Use the HTTP payload and endpoint described in `development-guide/stage-05-github-snapshot-ingest.md` (for example, a `POST` to the API with a GitHub repo URL and branch).  
   - Confirm snapshot metadata appears in Postgres and raw artifacts are stored in MinIO.

4. **Run downstream pipeline stages**  
   - Follow the sequence defined in the stage guides (log ingest, parsing, topology enrichment, embeddings, GNN, classifier, etc.).  
   - For each stage, re‑use the same endpoints as in the guides, but with `http://localhost:<port>` as the base URL.

5. **Verify MLflow tracking (Stage 26)**  
   - Run one of the tracked trainers (e.g. anomaly or GNN) via its API or CLI entry point.  
   - Open `http://localhost:5000` and confirm runs, parameters, metrics, and artifacts are logged.

6. **Run evaluation (Stage 27)**  
   - Trigger the evaluation pipeline as described in the evaluation guide to compute retrieval/synthesis/embedding metrics and log them to MLflow.

7. **Check observability (Stage 30)**  
   - Hit `/actuator/prometheus` on your services and confirm FlowForge metrics (stage duration, LLM tokens, embedding throughput, etc.) are present.  
   - If you choose to run Prometheus/Grafana locally, import `k8s/grafana/flowforge-pipeline-dashboard.json` into Grafana and inspect dashboards.

8. **Get the research flows output (system-flows-research.md)**  
   - After a research run completes for a snapshot, the markdown is published to MinIO and exposed via the API:
     - **API** (local port 9080):  
       `GET /api/v1/research/output/{snapshotId}`  
       Returns the markdown with `Content-Disposition: attachment; filename="system-flows-research.md"`.  
       Example: `curl -o system-flows-research.md http://localhost:9080/api/v1/research/output/<snapshot-id>`
     - **MinIO**: bucket `output`, object key `system-flows-research/{snapshotId}/system-flows-research.md` (and `document.json` in the same prefix).

---

## 5. What is *not* covered by local testing

The following require a Kubernetes cluster (e.g. AKS) and are **not** exercised by this Docker Desktop setup:

- Argo Workflows DAG orchestration (Stage 28)
- Dapr sidecar integration and production pub/sub/state/secret components (Stage 29)
- ArgoCD GitOps deployment and Helm‑based observability stack (Prometheus/Grafana/Tempo) in `k8s/argocd/apps/**`

Once you are confident that:

- The local stack builds,
- Core pipeline APIs behave as expected end‑to‑end,
- MLflow and basic observability behave correctly,

you can move on to applying the `k8s/**` manifests and ArgoCD Applications to a real AKS cluster for full‑fidelity testing.

---

## 6. LLM via Ollama (local and AKS)

FlowForge can now use **Ollama** as the backing `ChatModel` (via Spring AI) when `flowforge.ollama.*` properties are configured.

### 6.1 Local Ollama with Docker Desktop

For a purely local setup without GPUs, you can add an `ollama` service to your `docker-compose.yml`:

```yaml
  ollama:
    image: ollama/ollama:latest
    container_name: ff-ollama
    command: serve
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
```

And add the volume:

```yaml
volumes:
  pg_data:
  minio_data:
  neo4j_data:
  qdrant_data:
  mlflow_data:
  ollama_data:
```

Start (or restart) the stack:

```bash
docker compose up -d
```

Once the container is running, open a shell into it and pull a model, for example:

```bash
docker exec -it ff-ollama ollama pull llama3.1
```

Then configure your Spring Boot services (e.g. in `application-local.yml`) to use Ollama:

```yaml
flowforge:
  ollama:
    base-url: http://localhost:11434
    chat-model: llama3.1
```

With this in place, the `LlmConfig.chatModel` bean will use `OllamaChatModel` instead of the vLLM OpenAI-compatible endpoint.

### 6.2 Ollama on AKS via ArgoCD

For AKS, Ollama is deployed as a separate workload:

- **ArgoCD Application**: `k8s/argocd/apps/ollama.yaml`
- **Workload manifests**: `k8s/ml-serving/ollama/deployment.yaml`, `service.yaml`, `kustomization.yaml`

High-level steps:

1. Apply or sync the **App-of-Apps** ArgoCD root (`k8s/argocd/app-of-apps.yaml`) so that `flowforge-ollama` is created.
2. In ArgoCD, sync the `flowforge-ollama` Application. This deploys:
   - Deployment `ollama` in namespace `flowforge-ml`
   - Service `ollama` on port `11434`
3. Configure your services (for the AKS profile) with:

```yaml
flowforge:
  ollama:
    base-url: http://ollama.flowforge-ml.svc.cluster.local:11434
    chat-model: llama3.1
```

After this, any component that depends on `ChatModel` via `LlmConfig` will use the Ollama-backed model in the cluster, while your local Docker Desktop setup can still use its own Ollama instance for development. 

