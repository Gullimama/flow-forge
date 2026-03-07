# Stage 05 — GitHub Snapshot Ingestion

## Goal

Build the GitHub ingestion service that clones or refreshes a private GitHub repository, classifies files by type and service, and stores the snapshot in MinIO with full metadata tracking in PostgreSQL.

## Prerequisites

- Stage 01 (project structure)
- Stage 02 (PostgreSQL — snapshot tracking)
- Stage 03 (MinIO — raw-git bucket)
- Stage 04 (API — snapshot endpoint triggers this service)

## What to build

### 5.1 GitHub client

```java
@Component
public class GitHubSnapshotClient {

    private final FlowForgeProperties props;

    /** Clone the repository at a specific branch/commit. */
    public Path cloneRepository(String repoUrl, String branch, String token, Path targetDir) { ... }

    /** Get HEAD commit SHA for a branch. */
    public String getHeadCommitSha(String repoUrl, String branch, String token) { ... }

    /** Get list of changed files between two commit SHAs. */
    public List<String> getChangedFiles(String repoUrl, String baseSha, String headSha, String token) { ... }
}
```

### 5.2 File classifier

```java
@Component
public class FileClassifier {

    public record ClassifiedFile(
        String relativePath,
        FileType fileType,
        String serviceName,
        String module
    ) {}

    public enum FileType {
        JAVA_SOURCE, YAML_CONFIG, PROPERTIES_CONFIG,
        K8S_MANIFEST, ISTIO_MANIFEST, HELM_CHART,
        BUILD_FILE, DOCKERFILE, OTHER
    }

    /** Classify a file by its path and extension. */
    public ClassifiedFile classify(String relativePath) { ... }

    /** Detect service name from file path convention. */
    public String detectServiceName(String relativePath) { ... }
}
```

### 5.3 Snapshot ingestion worker

```java
@Service
public class GitHubSnapshotWorker {

    private final GitHubSnapshotClient gitClient;
    private final FileClassifier classifier;
    private final MinioStorageClient storage;
    private final MetadataService metadata;

    /**
     * Execute a baseline snapshot:
     * 1. Clone full repository
     * 2. Walk file tree, classify each file
     * 3. Upload to MinIO: raw-git/<snapshot_id>/source/<service>/<path>
     * 4. Upload manifests to: raw-git/<snapshot_id>/manifests/
     * 5. Track in PostgreSQL snapshots + parse_artifacts
     * 6. Return snapshot metadata
     */
    public SnapshotResult executeBaseline(UUID jobId, String repoUrl, String branch) { ... }

    /**
     * Execute a refresh snapshot:
     * 1. Get latest snapshot from PostgreSQL
     * 2. Get changed files since last commit SHA
     * 3. Partial clone or sparse checkout of changed files
     * 4. Upload only changed files to MinIO (new snapshot_id)
     * 5. Track changed_files list in snapshot metadata
     */
    public SnapshotResult executeRefresh(UUID jobId) { ... }
}
```

```java
public record SnapshotResult(
    UUID snapshotId,
    String commitSha,
    int totalFiles,
    int javaFiles,
    int configFiles,
    int manifestFiles,
    List<String> detectedServices,
    Map<FileClassifier.FileType, Integer> fileTypeCounts
) {}
```

### 5.4 Dependencies

```kotlin
dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    implementation(project(":libs:common"))
}
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| MinIO | `flowforge-minio.flowforge-infra.svc.cluster.local` | 9000 |

> **Secret:** The `GITHUB_TOKEN` is provisioned from Azure Key Vault via the Dapr secret store (see Stage 29).

**Argo task resource class:** CPU (`cpupool` node selector)

---

## Testing & Verification Strategy

### Unit Tests

**Test class:** `libs/ingest/src/test/java/com/flowforge/ingest/github/FileClassifierTest.java`

Tests file type detection and service name extraction from path conventions.

```java
class FileClassifierTest {

    private final FileClassifier classifier = new FileClassifier();

    @Test
    void classifiesJavaSourceFile() {
        var result = classifier.classify("services/booking/src/main/java/com/example/BookingService.java");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.JAVA_SOURCE);
        assertThat(result.serviceName()).isEqualTo("booking");
    }

    @Test
    void classifiesYamlConfigFile() {
        var result = classifier.classify("services/payment/src/main/resources/application.yml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.YAML_CONFIG);
        assertThat(result.serviceName()).isEqualTo("payment");
    }

    @Test
    void classifiesKubernetesManifest() {
        var result = classifier.classify("k8s/deployments/booking-deployment.yaml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.K8S_MANIFEST);
    }

    @Test
    void classifiesIstioManifest() {
        var result = classifier.classify("istio/virtual-service-booking.yaml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.ISTIO_MANIFEST);
    }

    @Test
    void classifiesHelmChart() {
        var result = classifier.classify("helm/charts/booking/templates/deployment.yaml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.HELM_CHART);
    }

    @Test
    void classifiesDockerfile() {
        var result = classifier.classify("services/booking/Dockerfile");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.DOCKERFILE);
    }

    @Test
    void classifiesPropertiesConfig() {
        var result = classifier.classify("services/api/src/main/resources/application.properties");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.PROPERTIES_CONFIG);
    }

    @Test
    void fallsBackToOtherForUnknownExtension() {
        var result = classifier.classify("README.md");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.OTHER);
    }

    @ParameterizedTest
    @CsvSource({
        "services/booking/src/Main.java, booking",
        "services/payment-service/src/Pay.java, payment-service",
        "libs/common/src/Util.java, common"
    })
    void detectsServiceNameFromPath(String path, String expectedService) {
        assertThat(classifier.detectServiceName(path)).isEqualTo(expectedService);
    }
}
```

**Test class:** `libs/ingest/src/test/java/com/flowforge/ingest/github/GitHubSnapshotClientTest.java`

Tests JGit operations against a local temporary Git repository.

```java
class GitHubSnapshotClientTest {

    private GitHubSnapshotClient client;
    private Path tempRepo;

    @BeforeEach
    void setUp() throws Exception {
        client = new GitHubSnapshotClient();
        tempRepo = Files.createTempDirectory("test-repo");
        try (Git git = Git.init().setDirectory(tempRepo.toFile()).call()) {
            Files.writeString(tempRepo.resolve("README.md"), "# Test");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        FileUtils.deleteDirectory(tempRepo.toFile());
    }

    @Test
    void cloneRepositoryCreatesLocalCopy() throws Exception {
        Path cloneDir = Files.createTempDirectory("clone");
        Path result = client.cloneRepository(
            tempRepo.toUri().toString(), "master", null, cloneDir);

        assertThat(result.resolve("README.md")).exists();
        FileUtils.deleteDirectory(cloneDir.toFile());
    }

    @Test
    void getHeadCommitShaReturnsValidSha() throws Exception {
        String sha = client.getHeadCommitSha(
            tempRepo.toUri().toString(), "master", null);

        assertThat(sha).hasSize(40);
        assertThat(sha).matches("[a-f0-9]{40}");
    }

    @Test
    void getChangedFilesDetectsNewCommit() throws Exception {
        String baseSha;
        try (Git git = Git.open(tempRepo.toFile())) {
            baseSha = git.log().setMaxCount(1).call().iterator().next()
                .getName();
            Files.writeString(tempRepo.resolve("NewFile.java"), "class New {}");
            Files.writeString(tempRepo.resolve("Another.java"), "class Another {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add files").call();
        }

        String headSha = client.getHeadCommitSha(
            tempRepo.toUri().toString(), "master", null);
        List<String> changed = client.getChangedFiles(
            tempRepo.toUri().toString(), baseSha, headSha, null);

        assertThat(changed).containsExactlyInAnyOrder("NewFile.java", "Another.java");
    }
}
```

**Test class:** `libs/ingest/src/test/java/com/flowforge/ingest/github/GitHubSnapshotWorkerUnitTest.java`

Tests worker orchestration logic with mocked dependencies.

```java
@ExtendWith(MockitoExtension.class)
class GitHubSnapshotWorkerUnitTest {

    @Mock private GitHubSnapshotClient gitClient;
    @Mock private FileClassifier classifier;
    @Mock private MinioStorageClient storage;
    @Mock private MetadataService metadata;

    @InjectMocks private GitHubSnapshotWorker worker;

    @Test
    void executeBaselineUploadsClassifiedFilesToMinio() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path fakeClone = Files.createTempDirectory("fake-clone");
        Files.writeString(fakeClone.resolve("App.java"), "class App {}");

        when(gitClient.cloneRepository(any(), any(), any(), any()))
            .thenReturn(fakeClone);
        when(gitClient.getHeadCommitSha(any(), any(), any()))
            .thenReturn("abc123");
        when(classifier.classify("App.java"))
            .thenReturn(new FileClassifier.ClassifiedFile(
                "App.java", FileClassifier.FileType.JAVA_SOURCE, "api", "main"));
        when(metadata.createSnapshot(any())).thenReturn(UUID.randomUUID());

        SnapshotResult result = worker.executeBaseline(
            jobId, "https://github.com/org/repo", "master");

        assertThat(result.totalFiles()).isGreaterThanOrEqualTo(1);
        verify(storage, atLeastOnce()).putObject(eq("raw-git"), any(), any(), any());
        verify(metadata).createSnapshot(any());

        FileUtils.deleteDirectory(fakeClone.toFile());
    }

    @Test
    void executeRefreshOnlyUploadsChangedFiles() throws Exception {
        UUID jobId = UUID.randomUUID();
        var latestSnapshot = new SnapshotEntity();
        latestSnapshot.setSnapshotId(UUID.randomUUID());
        latestSnapshot.setCommitSha("old-sha");
        latestSnapshot.setRepoUrl("https://github.com/org/repo");
        latestSnapshot.setBranch("master");
        when(metadata.getLatestSnapshot()).thenReturn(Optional.of(latestSnapshot));
        when(gitClient.getHeadCommitSha(any(), any(), any()))
            .thenReturn("new-sha");
        when(gitClient.getChangedFiles(any(), eq("old-sha"), eq("new-sha"), any()))
            .thenReturn(List.of("Changed.java"));

        Path fakeClone = Files.createTempDirectory("fake-refresh");
        Files.writeString(fakeClone.resolve("Changed.java"), "class Changed {}");
        when(gitClient.cloneRepository(any(), any(), any(), any()))
            .thenReturn(fakeClone);
        when(classifier.classify("Changed.java"))
            .thenReturn(new FileClassifier.ClassifiedFile(
                "Changed.java", FileClassifier.FileType.JAVA_SOURCE, "api", "main"));
        when(metadata.createSnapshot(any())).thenReturn(UUID.randomUUID());

        SnapshotResult result = worker.executeRefresh(jobId);

        assertThat(result.totalFiles()).isEqualTo(1);
        verify(storage, times(1)).putObject(eq("raw-git"), contains("Changed.java"), any(), any());

        FileUtils.deleteDirectory(fakeClone.toFile());
    }
}
```

### Integration Tests

**Test class:** `libs/ingest/src/test/java/com/flowforge/ingest/github/GitHubSnapshotWorkerIntegrationTest.java`

End-to-end test with Testcontainers PostgreSQL and MinIO, using a local temporary Git repository as the source.

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class GitHubSnapshotWorkerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge");

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest")
        .withUserName("minioadmin")
        .withPassword("minioadmin");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("flowforge.minio.endpoint", minio::getS3URL);
        registry.add("flowforge.minio.access-key", () -> "minioadmin");
        registry.add("flowforge.minio.secret-key", () -> "minioadmin");
    }

    @Autowired private GitHubSnapshotWorker worker;
    @Autowired private MetadataService metadataService;
    @Autowired private MinioStorageClient storageClient;

    private Path localRepo;

    @BeforeEach
    void setUpRepo() throws Exception {
        storageClient.ensureBuckets();
        localRepo = Files.createTempDirectory("integration-repo");
        try (Git git = Git.init().setDirectory(localRepo.toFile()).call()) {
            Files.createDirectories(localRepo.resolve("services/booking/src/main/java"));
            Files.writeString(
                localRepo.resolve("services/booking/src/main/java/Booking.java"),
                "public class Booking {}");
            Files.writeString(
                localRepo.resolve("services/booking/src/main/resources/application.yml"),
                "server:\n  port: 8080");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial commit").call();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        FileUtils.deleteDirectory(localRepo.toFile());
    }

    @Test
    void baselineSnapshotEndToEnd() {
        UUID jobId = metadataService.createJob("SNAPSHOT", Map.of());

        SnapshotResult result = worker.executeBaseline(
            jobId, localRepo.toUri().toString(), "master");

        assertThat(result.snapshotId()).isNotNull();
        assertThat(result.totalFiles()).isGreaterThanOrEqualTo(2);
        assertThat(result.javaFiles()).isGreaterThanOrEqualTo(1);
        assertThat(result.detectedServices()).contains("booking");

        // Verify MinIO structure
        var objects = storageClient.listObjects("raw-git",
            result.snapshotId().toString() + "/");
        assertThat(objects).isNotEmpty();

        // Verify PostgreSQL tracking
        var snapshot = metadataService.getSnapshot(result.snapshotId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().getStatus()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void refreshSnapshotOnlyUploadsChangedFiles() throws Exception {
        // Baseline
        UUID baselineJobId = metadataService.createJob("SNAPSHOT", Map.of());
        worker.executeBaseline(baselineJobId, localRepo.toUri().toString(), "master");

        // Add a new file and commit
        try (Git git = Git.open(localRepo.toFile())) {
            Files.writeString(
                localRepo.resolve("services/booking/src/main/java/NewService.java"),
                "public class NewService {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add NewService").call();
        }

        // Refresh
        UUID refreshJobId = metadataService.createJob("SNAPSHOT_REFRESH", Map.of());
        SnapshotResult refreshResult = worker.executeRefresh(refreshJobId);

        assertThat(refreshResult.snapshotId()).isNotNull();
        assertThat(refreshResult.totalFiles()).isEqualTo(1);

        var snapshot = metadataService.getSnapshot(refreshResult.snapshotId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().getSnapshotType()).isEqualTo(SnapshotType.REFRESH);
    }
}
```

### Test Fixtures & Sample Data

| File | Description |
|---|---|
| `src/test/resources/fixtures/sample-repo/` | A minimal Git-initialized directory with Java, YAML, and K8s files for local clone testing |
| `src/test/resources/fixtures/file-classification-cases.csv` | CSV of `(path, expectedFileType, expectedService)` tuples for parameterized `FileClassifier` tests |
| `src/test/resources/fixtures/snapshot-result-baseline.json` | Expected `SnapshotResult` for a baseline run |
| `src/test/resources/fixtures/snapshot-result-refresh.json` | Expected `SnapshotResult` for a refresh with 2 changed files |
| `src/test/resources/fixtures/changed-files-diff.txt` | Sample JGit diff output for 3 changed files |

The `sample-repo/` fixture should contain:
```
sample-repo/
├── services/
│   └── booking/
│       ├── src/main/java/Booking.java
│       └── src/main/resources/application.yml
├── k8s/
│   └── booking-deployment.yaml
├── istio/
│   └── virtual-service-booking.yaml
├── helm/
│   └── charts/booking/Chart.yaml
└── Dockerfile
```

### Mocking Strategy

| Component | Strategy |
|---|---|
| `GitHubSnapshotClient` | **Mocked** in worker unit tests — return pre-built paths and SHA values |
| `FileClassifier` | **Mocked** in worker unit tests; **real** in its own unit tests and integration tests |
| `MinioStorageClient` | **Mocked** in worker unit tests; **real** via Testcontainers MinIO in integration tests |
| `MetadataService` | **Mocked** in worker unit tests; **real** via Testcontainers PostgreSQL in integration tests |
| Git repositories | **Local temp repos** (JGit `Git.init()`) — never call external GitHub in tests |
| GitHub API / tokens | Never used in tests — all clone operations use local `file://` URIs |

### CI/CD Considerations

- **Test tags:** `@Tag("unit")` for `FileClassifierTest`, `GitHubSnapshotClientTest`, and worker unit tests. `@Tag("integration")` for `GitHubSnapshotWorkerIntegrationTest`.
- **Docker requirements:** CI runners need Docker for Testcontainers PostgreSQL and MinIO. Both containers start in parallel, total cold-start ~5 seconds.
- **No external network:** Tests use local temp Git repos with `file://` URIs. No GitHub API calls or tokens required in CI.
- **Temp directory cleanup:** All tests use `@AfterEach` cleanup of `Files.createTempDirectory` paths. CI should set `java.io.tmpdir` to a CI-writable location.
- **CI pipeline stages:** `compile` → `unit-test` → `integration-test` (Docker: PostgreSQL + MinIO) → `lint`.
- **Performance gate:** The `baselineSnapshotEndToEnd` test should complete within 30 seconds. Add a JUnit 5 `@Timeout(30)` annotation for CI enforcement.
- **Shared Testcontainers:** Extend the `SharedPostgresTestBase` from Stage 02 and add a shared MinIO container to minimize container churn across test classes.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Clone repo | Unit test with local Git repo (JGit) | Files cloned to temp dir |
| Detect commit SHA | Unit test | Correct SHA returned |
| Changed files | Unit test: commit 2 files, query | 2 files returned |
| File classification | Unit test: Java, YAML, K8s files | Correct FileType |
| Service detection | `services/booking/src/...` → booking | Correct service name |
| Baseline snapshot | Integration test: clone → MinIO → PostgreSQL | All steps complete |
| Refresh snapshot | Integration test: baseline → change → refresh | Only changed files re-uploaded |
| MinIO structure | List raw-git/<snapshot_id>/ | Correct directory structure |
| Snapshot metadata | Query PostgreSQL after ingest | Metadata matches |
| Large repo performance | Clone repo with 1000+ files | Completes in < 5 min |

## Files to create

- `libs/ingest/build.gradle.kts`
- `libs/ingest/src/main/java/com/flowforge/ingest/github/GitHubSnapshotClient.java`
- `libs/ingest/src/main/java/com/flowforge/ingest/github/FileClassifier.java`
- `libs/ingest/src/main/java/com/flowforge/ingest/github/GitHubSnapshotWorker.java`
- `libs/ingest/src/main/java/com/flowforge/ingest/github/SnapshotResult.java`
- `libs/ingest/src/test/java/.../GitHubSnapshotClientTest.java`
- `libs/ingest/src/test/java/.../FileClassifierTest.java`
- `libs/ingest/src/test/java/.../GitHubSnapshotWorkerIntegrationTest.java`

## Depends on

- Stage 01, 02 (PostgreSQL), 03 (MinIO), 04 (API trigger)

## Produces

- Full repository snapshots stored in MinIO (raw-git/)
- File type classification (Java, config, K8s, Istio, Helm)
- Service name detection from path conventions
- Baseline and refresh snapshot support
- Snapshot metadata tracked in PostgreSQL
