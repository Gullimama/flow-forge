# Stage 02 — PostgreSQL Metadata Store

## Goal

Deploy PostgreSQL, define the operational metadata schema (jobs, snapshots, blob tracking, runs), and implement the Spring Data JPA repository layer that every other service will use for state tracking.

## Prerequisites

- Stage 01 completed (project structure, shared libs, Gradle config)

## What to build

### 2.1 PostgreSQL deployment

> **Local dev only:** a lightweight PostgreSQL container is defined in
> `docker/docker-compose.yml` for laptop use. Everything below targets the
> AKS production path via ArgoCD GitOps.

#### ArgoCD Application (`k8s/argocd/apps/postgresql.yaml`)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-postgresql
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "1"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  sources:
    - repoURL: https://charts.bitnami.com/bitnami
      chart: postgresql
      targetRevision: 16.*
      helm:
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
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
```

#### Helm values (`k8s/infrastructure/postgresql/values.yaml`)

```yaml
image:
  registry: docker.io
  repository: postgres
  tag: "16-alpine"

auth:
  database: flowforge
  username: flowforge
  existingSecret: flowforge-postgresql-credentials   # key: password
  secretKeys:
    userPasswordKey: password

primary:
  resources:
    requests:
      cpu: "1"
      memory: 2Gi
    limits:
      cpu: "2"
      memory: 4Gi
  persistence:
    enabled: true
    size: 50Gi
    storageClass: managed-csi
  nodeSelector:
    agentpool: cpupool
  readinessProbe:
    enabled: true
    initialDelaySeconds: 10
    periodSeconds: 10
    timeoutSeconds: 5
  livenessProbe:
    enabled: true
    initialDelaySeconds: 30
    periodSeconds: 10
    timeoutSeconds: 5
  initdb:
    scriptsConfigMap: flowforge-initdb
    # The Flyway migrations in V1__initial_schema.sql are applied by the
    # Spring Boot services on first connect; the initdb script here only
    # ensures the flowforge database and extensions exist.

metrics:
  enabled: true
  serviceMonitor:
    enabled: true
    namespace: flowforge-obs
```

### 2.2 Database schema (Flyway migrations)

Use Flyway for migrations. Create `V1__initial_schema.sql`:

```sql
-- Snapshot tracking
CREATE TABLE snapshots (
    snapshot_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_url        TEXT NOT NULL,
    branch          TEXT NOT NULL DEFAULT 'master',
    commit_sha      TEXT NOT NULL,
    snapshot_type   TEXT NOT NULL CHECK (snapshot_type IN ('BASELINE', 'REFRESH')),
    parent_snapshot UUID REFERENCES snapshots(snapshot_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_files   JSONB DEFAULT '[]'::jsonb,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    metadata        JSONB DEFAULT '{}'::jsonb
);

-- Blob ingestion tracking
CREATE TABLE blob_ingestion_batches (
    batch_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    storage_account TEXT NOT NULL,
    container       TEXT NOT NULL,
    prefix          TEXT NOT NULL DEFAULT '',
    mode            TEXT NOT NULL CHECK (mode IN ('FULL', 'INCREMENTAL')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    blob_count      INTEGER DEFAULT 0,
    metadata        JSONB DEFAULT '{}'::jsonb
);

CREATE TABLE blob_records (
    id              BIGSERIAL PRIMARY KEY,
    batch_id        UUID NOT NULL REFERENCES blob_ingestion_batches(batch_id),
    blob_name       TEXT NOT NULL,
    etag            TEXT NOT NULL,
    content_length  BIGINT NOT NULL,
    last_modified   TIMESTAMPTZ NOT NULL,
    log_type        TEXT CHECK (log_type IN ('APP', 'ISTIO', 'UNKNOWN')),
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'DOWNLOADED', 'EXTRACTED', 'PARSED', 'FAILED')),
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (batch_id, blob_name, etag)
);

-- Job tracking
CREATE TABLE jobs (
    job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type        TEXT NOT NULL,
    parent_job      UUID REFERENCES jobs(job_id),
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    progress_pct    REAL DEFAULT 0.0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    input_params    JSONB DEFAULT '{}'::jsonb,
    output_refs     JSONB DEFAULT '{}'::jsonb,
    metadata        JSONB DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0
);

-- Research run tracking
CREATE TABLE research_runs (
    run_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id     UUID NOT NULL REFERENCES snapshots(snapshot_id),
    blob_batch_id   UUID REFERENCES blob_ingestion_batches(batch_id),
    job_id          UUID NOT NULL REFERENCES jobs(job_id),
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    models_manifest JSONB DEFAULT '{}'::jsonb,
    pipeline_config JSONB DEFAULT '{}'::jsonb,
    quality_metrics JSONB DEFAULT '{}'::jsonb,
    output_path     TEXT
);

-- Parser artifact tracking
CREATE TABLE parse_artifacts (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_id     UUID NOT NULL REFERENCES snapshots(snapshot_id),
    artifact_type   TEXT NOT NULL,
    artifact_key    TEXT NOT NULL,
    content_hash    TEXT NOT NULL,
    minio_path      TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PARSED', 'INDEXED', 'EMBEDDED', 'FAILED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata        JSONB DEFAULT '{}'::jsonb,
    UNIQUE (snapshot_id, artifact_type, artifact_key)
);

-- Indexes
CREATE INDEX idx_snapshots_status ON snapshots(status);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_type ON jobs(job_type);
CREATE INDEX idx_blob_records_batch ON blob_records(batch_id);
CREATE INDEX idx_blob_records_status ON blob_records(batch_id, status);
CREATE INDEX idx_parse_artifacts_snapshot ON parse_artifacts(snapshot_id);
CREATE INDEX idx_research_runs_status ON research_runs(status);
```

### 2.3 JPA entities

```java
@Entity
@Table(name = "snapshots")
public class SnapshotEntity {
    @Id
    private UUID snapshotId;
    private String repoUrl;
    private String branch;
    private String commitSha;
    @Enumerated(EnumType.STRING)
    private SnapshotType snapshotType;
    private UUID parentSnapshot;
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> changedFiles;
    @Enumerated(EnumType.STRING)
    private Status status;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;
}

@Entity
@Table(name = "jobs")
public class JobEntity {
    @Id
    private UUID jobId;
    private String jobType;
    private UUID parentJob;
    @Enumerated(EnumType.STRING)
    private Status status;
    private float progressPct;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> inputParams;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> outputRefs;
    @Version
    private long version;
}
```

### 2.4 Spring Data JPA repositories

```java
public interface SnapshotRepository extends JpaRepository<SnapshotEntity, UUID> {
    Optional<SnapshotEntity> findTopByStatusOrderByCreatedAtDesc(Status status);
    Optional<SnapshotEntity> findTopByOrderByCreatedAtDesc();
    List<SnapshotEntity> findByStatus(Status status);
}

public interface JobRepository extends JpaRepository<JobEntity, UUID> {
    List<JobEntity> findByStatus(Status status);
    List<JobEntity> findByJobType(String jobType);

    @Modifying @Query("UPDATE JobEntity j SET j.status = :status, j.progressPct = :progress WHERE j.jobId = :id")
    void updateStatus(@Param("id") UUID jobId, @Param("status") Status status, @Param("progress") float progress);
}

public interface BlobBatchRepository extends JpaRepository<BlobBatchEntity, UUID> {}
public interface BlobRecordRepository extends JpaRepository<BlobRecordEntity, Long> {
    List<BlobRecordEntity> findByBatchIdAndStatus(UUID batchId, Status status);
    boolean existsByEtag(String etag);
}

public interface ResearchRunRepository extends JpaRepository<ResearchRunEntity, UUID> {
    Optional<ResearchRunEntity> findTopByStatusOrderByCreatedAtDesc(Status status);
}

public interface ParseArtifactRepository extends JpaRepository<ParseArtifactEntity, Long> {
    List<ParseArtifactEntity> findBySnapshotId(UUID snapshotId);
    Optional<ParseArtifactEntity> findBySnapshotIdAndArtifactTypeAndArtifactKey(
        UUID snapshotId, String type, String key);
}
```

### 2.5 Metadata service layer

```java
@Service
@Transactional
public class MetadataService {
    private final SnapshotRepository snapshots;
    private final JobRepository jobs;
    private final BlobBatchRepository blobBatches;
    private final BlobRecordRepository blobRecords;
    private final ResearchRunRepository researchRuns;
    private final ParseArtifactRepository parseArtifacts;

    public UUID createSnapshot(SnapshotMetadata meta) { ... }
    public Optional<SnapshotEntity> getSnapshot(UUID id) { ... }
    public Optional<SnapshotEntity> getLatestSnapshot() { ... }
    public void updateSnapshotStatus(UUID id, Status status) { ... }

    public UUID createJob(String jobType, Map<String, Object> params) { ... }
    public void updateJobStatus(UUID jobId, Status status, float progress) { ... }

    public UUID createBlobBatch(BlobBatchConfig config) { ... }
    public void recordBlob(UUID batchId, BlobIngestionRecord record) { ... }
    public List<BlobRecordEntity> getUnprocessedBlobs(UUID batchId) { ... }

    public UUID createResearchRun(UUID snapshotId, UUID blobBatchId) { ... }
    public Optional<ResearchRunEntity> getLatestResearchRun() { ... }

    public void upsertParseArtifact(ParseArtifactEntity artifact) { ... }
    public List<String> getChangedArtifacts(UUID snapshotId, Map<String, String> hashes) { ... }

    public Optional<JobEntity> getJob(UUID jobId) {
        return jobRepo.findById(jobId);
    }

    public List<JobEntity> getJobsBySnapshot(UUID snapshotId) {
        return jobRepo.findBySnapshotId(snapshotId);
    }

    public boolean existsBlobByEtag(String etag) {
        return blobRecordRepo.existsByEtag(etag);
    }
}
```

### 2.6 Dependencies (in `build.gradle.kts`)

```kotlin
dependencies {
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}
```

## Testing & Verification Strategy

### Unit Tests

**Test class:** `libs/common/src/test/java/com/flowforge/common/entity/EntityMappingTest.java`

Validates JPA entity field mappings and enum conversions without a database.

```java
class EntityMappingTest {

    @Test
    void snapshotEntityDefaultValues() {
        var entity = new SnapshotEntity();
        entity.setSnapshotId(UUID.randomUUID());
        entity.setRepoUrl("https://github.com/org/repo");
        entity.setBranch("master");
        entity.setCommitSha("abc123");
        entity.setSnapshotType(SnapshotType.BASELINE);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(Status.PENDING);

        assertThat(entity.getSnapshotId()).isNotNull();
        assertThat(entity.getSnapshotType()).isEqualTo(SnapshotType.BASELINE);
    }

    @Test
    void jobEntityVersionFieldStartsAtZero() {
        var entity = new JobEntity();
        assertThat(entity.getVersion()).isEqualTo(0L);
    }

    @Test
    void jobEntityStatusEnumValuesMatch() {
        assertThat(Status.values()).contains(
            Status.PENDING, Status.RUNNING, Status.COMPLETED,
            Status.FAILED, Status.CANCELLED
        );
    }
}
```

**Test class:** `libs/common/src/test/java/com/flowforge/common/service/MetadataServiceUnitTest.java`

Tests `MetadataService` business logic with mocked repositories.

```java
@ExtendWith(MockitoExtension.class)
class MetadataServiceUnitTest {

    @Mock private SnapshotRepository snapshotRepo;
    @Mock private JobRepository jobRepo;
    @Mock private BlobBatchRepository blobBatchRepo;
    @Mock private BlobRecordRepository blobRecordRepo;
    @Mock private ResearchRunRepository researchRunRepo;
    @Mock private ParseArtifactRepository parseArtifactRepo;

    @InjectMocks private MetadataService metadataService;

    @Test
    void createSnapshotPersistsEntityAndReturnsId() {
        var meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha-1", SnapshotType.BASELINE, Instant.now(), List.of()
        );
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID id = metadataService.createSnapshot(meta);

        assertThat(id).isNotNull();
        verify(snapshotRepo).save(any(SnapshotEntity.class));
    }

    @Test
    void updateJobStatusDelegatesToRepository() {
        UUID jobId = UUID.randomUUID();

        metadataService.updateJobStatus(jobId, Status.RUNNING, 25.0f);

        verify(jobRepo).updateStatus(jobId, Status.RUNNING, 25.0f);
    }

    @Test
    void getUnprocessedBlobsFiltersCorrectly() {
        UUID batchId = UUID.randomUUID();
        var pending = new BlobRecordEntity();
        pending.setStatus(Status.PENDING);
        when(blobRecordRepo.findByBatchIdAndStatus(batchId, Status.PENDING))
            .thenReturn(List.of(pending));

        var result = metadataService.getUnprocessedBlobs(batchId);

        assertThat(result).hasSize(1);
    }

    @Test
    void upsertParseArtifactUpdatesExistingRecord() {
        var existing = new ParseArtifactEntity();
        existing.setContentHash("old-hash");
        when(parseArtifactRepo.findBySnapshotIdAndArtifactTypeAndArtifactKey(
            any(), any(), any())).thenReturn(Optional.of(existing));

        var artifact = new ParseArtifactEntity();
        artifact.setSnapshotId(UUID.randomUUID());
        artifact.setArtifactType("JAVA_CLASS");
        artifact.setArtifactKey("com.example.Foo");
        artifact.setContentHash("new-hash");

        metadataService.upsertParseArtifact(artifact);

        verify(parseArtifactRepo).save(any());
    }
}
```

### Integration Tests

**Test class:** `libs/common/src/test/java/com/flowforge/common/repository/RepositoryIntegrationTest.java`

Uses Testcontainers PostgreSQL to run Flyway migrations and validate CRUD operations against a real database.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Tag("integration")
class RepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private SnapshotRepository snapshotRepo;
    @Autowired private JobRepository jobRepo;
    @Autowired private ParseArtifactRepository parseArtifactRepo;

    @Test
    void flywayMigrationsRunSuccessfully() {
        assertThat(snapshotRepo.count()).isZero();
    }

    @Test
    void snapshotCrudRoundTrip() {
        var entity = new SnapshotEntity();
        entity.setSnapshotId(UUID.randomUUID());
        entity.setRepoUrl("https://github.com/org/repo");
        entity.setBranch("master");
        entity.setCommitSha("abc123");
        entity.setSnapshotType(SnapshotType.BASELINE);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(Status.PENDING);

        snapshotRepo.save(entity);
        var found = snapshotRepo.findById(entity.getSnapshotId());

        assertThat(found).isPresent();
        assertThat(found.get().getCommitSha()).isEqualTo("abc123");
    }

    @Test
    void jobOptimisticLockingThrowsOnConcurrentUpdate() {
        var job = new JobEntity();
        job.setJobId(UUID.randomUUID());
        job.setJobType("SNAPSHOT");
        job.setStatus(Status.PENDING);
        job.setCreatedAt(Instant.now());
        jobRepo.saveAndFlush(job);

        var copy1 = jobRepo.findById(job.getJobId()).orElseThrow();
        var copy2 = jobRepo.findById(job.getJobId()).orElseThrow();

        copy1.setStatus(Status.RUNNING);
        jobRepo.saveAndFlush(copy1);

        copy2.setStatus(Status.FAILED);
        assertThrows(OptimisticLockingFailureException.class,
            () -> jobRepo.saveAndFlush(copy2));
    }

    @Test
    void parseArtifactUpsertBehavior() {
        UUID snapshotId = UUID.randomUUID();
        var snapshot = new SnapshotEntity();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setRepoUrl("https://github.com/org/repo");
        snapshot.setBranch("master");
        snapshot.setCommitSha("sha1");
        snapshot.setSnapshotType(SnapshotType.BASELINE);
        snapshot.setCreatedAt(Instant.now());
        snapshot.setStatus(Status.COMPLETED);
        snapshotRepo.save(snapshot);

        var artifact = new ParseArtifactEntity();
        artifact.setSnapshotId(snapshotId);
        artifact.setArtifactType("JAVA_CLASS");
        artifact.setArtifactKey("com.example.Foo");
        artifact.setContentHash("hash-v1");
        artifact.setMinioPath("parsed-code/snap/Foo.json");
        artifact.setStatus(Status.PARSED);
        artifact.setCreatedAt(Instant.now());
        parseArtifactRepo.save(artifact);

        var existing = parseArtifactRepo.findBySnapshotIdAndArtifactTypeAndArtifactKey(
            snapshotId, "JAVA_CLASS", "com.example.Foo");
        assertThat(existing).isPresent();
        assertThat(existing.get().getContentHash()).isEqualTo("hash-v1");
    }
}
```

**Test class:** `libs/common/src/test/java/com/flowforge/common/service/MetadataServiceIntegrationTest.java`

End-to-end test of the `MetadataService` transactional flows.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class MetadataServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MetadataService metadataService;

    @Test
    void fullSnapshotLifecycle() {
        var meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha-1", SnapshotType.BASELINE, Instant.now(), List.of()
        );
        UUID snapshotId = metadataService.createSnapshot(meta);
        metadataService.updateSnapshotStatus(snapshotId, Status.COMPLETED);

        var latest = metadataService.getLatestSnapshot();
        assertThat(latest).isPresent();
        assertThat(latest.get().getStatus()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void connectionPoolHandlesConcurrentOperations() throws Exception {
        int threadCount = 20;
        var latch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    metadataService.createJob("LOAD_TEST", Map.of());
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        assertThat(errors.get()).isZero();
    }
}
```

### Test Fixtures & Sample Data

| File | Description |
|---|---|
| `V1__initial_schema.sql` | The Flyway migration itself serves as the DDL fixture — Testcontainers auto-applies it |
| `src/test/resources/application-test.yml` | Datasource config pointing at the Testcontainers dynamic port |
| `src/test/resources/fixtures/snapshot-entity.json` | Pre-built `SnapshotEntity` JSON for data-driven tests |
| `src/test/resources/fixtures/job-entity-pending.json` | `JobEntity` in PENDING state |
| `src/test/resources/fixtures/job-entity-completed.json` | `JobEntity` in COMPLETED state with output refs |
| `src/test/resources/fixtures/blob-batch-with-records.json` | `BlobBatchEntity` with 3 associated `BlobRecordEntity` entries |

### Mocking Strategy

| Component | Strategy |
|---|---|
| PostgreSQL | **Real** via Testcontainers `PostgreSQLContainer` for integration tests |
| `SnapshotRepository`, `JobRepository`, etc. | **Mocked** with Mockito `@Mock` in unit tests for `MetadataService` |
| `EntityManager` | Used implicitly by Spring Data — no direct mocking needed |
| Flyway | **Real** — runs against Testcontainers DB to validate migrations |
| `MetadataService` | **Real** in integration tests, tested against Testcontainers PostgreSQL |

### CI/CD Considerations

- **Test tags:** `@Tag("unit")` for entity/service unit tests, `@Tag("integration")` for Testcontainers-based tests.
- **Docker requirements:** CI runners must have Docker available for Testcontainers PostgreSQL. Use `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` for local dev speed.
- **Testcontainers singleton pattern:** Share a single `PostgreSQLContainer` across test classes to avoid per-class container startup overhead:
  ```java
  public abstract class SharedPostgresTestBase {
      static final PostgreSQLContainer<?> POSTGRES =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withDatabaseName("flowforge_test");

      static { POSTGRES.start(); }

      @DynamicPropertySource
      static void props(DynamicPropertyRegistry r) {
          r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
          r.add("spring.datasource.username", POSTGRES::getUsername);
          r.add("spring.datasource.password", POSTGRES::getPassword);
      }
  }
  ```
- **CI pipeline stages:** `compile` → `unit-test` → `integration-test` (requires Docker) → `lint`.
- **Migration validation:** Run `flyway validate` as a dedicated CI step to catch drift between migrations and entity mappings.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| PostgreSQL pod healthy | `kubectl get pods -n flowforge-infra -l app.kubernetes.io/name=postgresql` | Pod `Running`, `1/1 Ready` |
| ArgoCD app synced | `argocd app get flowforge-postgresql` | Status: `Synced`, Health: `Healthy` |
| Flyway migration runs | Application starts → Flyway auto-runs | All tables created |
| Migration rollback | Flyway undo/clean + re-apply | Clean cycle |
| Create snapshot | `@DataJpaTest`: insert + read back | Round-trip matches |
| Create job | `@DataJpaTest`: insert + status update + read | Status transitions work |
| Blob batch + records | `@DataJpaTest`: batch + 3 records + updates | All records tracked |
| Create research run | `@DataJpaTest`: run linked to snapshot + job | Foreign keys valid |
| Upsert parse artifact | `@DataJpaTest`: insert + update same key | Upsert works |
| Changed artifacts query | `@DataJpaTest`: 5 artifacts, change 2 | Returns 2 |
| Connection pool | Integration test: 20 concurrent operations | No connection errors |

## Files to create

- `services/api/src/main/resources/db/migration/V1__initial_schema.sql`
- `libs/common/src/main/java/com/flowforge/common/entity/SnapshotEntity.java`
- `libs/common/src/main/java/com/flowforge/common/entity/JobEntity.java`
- `libs/common/src/main/java/com/flowforge/common/entity/BlobBatchEntity.java`
- `libs/common/src/main/java/com/flowforge/common/entity/BlobRecordEntity.java`
- `libs/common/src/main/java/com/flowforge/common/entity/ResearchRunEntity.java`
- `libs/common/src/main/java/com/flowforge/common/entity/ParseArtifactEntity.java`
- `libs/common/src/main/java/com/flowforge/common/repository/SnapshotRepository.java`
- `libs/common/src/main/java/com/flowforge/common/repository/JobRepository.java`
- `libs/common/src/main/java/com/flowforge/common/repository/BlobBatchRepository.java`
- `libs/common/src/main/java/com/flowforge/common/repository/BlobRecordRepository.java`
- `libs/common/src/main/java/com/flowforge/common/repository/ResearchRunRepository.java`
- `libs/common/src/main/java/com/flowforge/common/repository/ParseArtifactRepository.java`
- `libs/common/src/main/java/com/flowforge/common/service/MetadataService.java`
- `k8s/argocd/apps/postgresql.yaml`
- `k8s/infrastructure/postgresql/values.yaml`
- `libs/common/src/test/java/com/flowforge/common/repository/SnapshotRepositoryTest.java`
- `libs/common/src/test/java/com/flowforge/common/repository/MetadataServiceIntegrationTest.java`

## Depends on

- Stage 01 (project structure, shared models, Gradle config)

## Produces

- AKS-deployed PostgreSQL (ArgoCD-managed, `flowforge-infra` namespace) with Flyway-managed schema
- JPA entities and Spring Data repositories for all tables
- Transactional `MetadataService` consumed by all services
