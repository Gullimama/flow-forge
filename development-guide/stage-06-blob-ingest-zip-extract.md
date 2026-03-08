# Stage 06 — Azure Blob Log Ingestion & Zip Extraction

## Goal

Build the log ingestion service that lists, downloads, and extracts Splunk log zip files from Azure Blob Storage, classifies log types, and stores extracted content in MinIO with tracking in PostgreSQL.

## Prerequisites

- Stage 01 (project structure)
- Stage 02 (PostgreSQL — blob batch/record tracking)
- Stage 03 (MinIO — raw-logs bucket)
- Stage 04 (API — log ingest endpoint triggers this)

## What to build

### 6.1 Azure Blob client

```java
@Component
public class AzureBlobClient {

    private final BlobServiceClient blobServiceClient;

    /** List all blobs matching prefix. */
    public List<BlobItem> listBlobs(String container, String prefix) { ... }

    /** Download a blob to a local temp file. */
    public Path downloadBlob(String container, String blobName, Path targetDir) { ... }

    /** Get blob properties (etag, size, last modified). */
    public BlobProperties getBlobProperties(String container, String blobName) { ... }
}
```

### 6.2 Zip extractor

```java
@Component
public class ZipExtractor {

    private static final long MAX_UNCOMPRESSED_SIZE = 10L * 1024 * 1024 * 1024; // 10 GB
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_COMPRESSION_RATIO = 100;

    /**
     * Extract a zip file with safety checks against zip bombs and path traversal.
     */
    public List<Path> extract(Path zipFile, Path targetDir) { ... }

    /**
     * Extract and classify: returns list of classified log files.
     * Supports .zip, .gz, and .tar.gz formats.
     */
    public List<ClassifiedLogFile> extractAndClassify(Path archiveFile, Path targetDir) {
        var filename = archiveFile.getFileName().toString().toLowerCase();
        if (filename.endsWith(".tar.gz") || filename.endsWith(".tgz")) {
            return extractTarGz(archiveFile, targetDir);
        } else if (filename.endsWith(".gz") && !filename.endsWith(".tar.gz")) {
            return extractGzip(archiveFile, targetDir);
        } else if (filename.endsWith(".zip")) {
            return extractZipAndClassify(archiveFile, targetDir);
        } else {
            // Plain text log file — copy directly
            return classifyPlainFile(archiveFile, targetDir);
        }
    }

    /**
     * Validate a zip entry name against path traversal attacks.
     */
    private Path safeResolve(Path targetDir, String entryName) {
        var resolved = targetDir.resolve(entryName).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new SecurityException(
                "Zip entry '%s' escapes target directory".formatted(entryName));
        }
        return resolved;
    }

    /**
     * Check cumulative extracted size against zip bomb threshold.
     */
    private void checkSizeLimit(long totalExtracted, long compressedSize) {
        if (totalExtracted > MAX_UNCOMPRESSED_SIZE) {
            throw new SecurityException(
                "Extracted size %d exceeds limit %d (potential zip bomb)"
                    .formatted(totalExtracted, MAX_UNCOMPRESSED_SIZE));
        }
        if (compressedSize > 0 && totalExtracted / compressedSize > MAX_COMPRESSION_RATIO) {
            throw new SecurityException(
                "Compression ratio %d exceeds limit %d (potential zip bomb)"
                    .formatted(totalExtracted / compressedSize, MAX_COMPRESSION_RATIO));
        }
    }

    public record ClassifiedLogFile(
        Path path,
        LogType logType,
        String serviceName,
        long sizeBytes
    ) {}

    public enum LogType { APP, ISTIO, UNKNOWN }
}
```

### 6.3 Log ingestion worker

```java
@Service
public class BlobIngestionWorker {

    /**
     * Full ingestion:
     * 1. List all zip blobs in Azure Blob container
     * 2. Create blob_ingestion_batch in PostgreSQL
     * 3. For each zip blob:
     *    a. Check etag — skip if already downloaded (idempotent)
     *    b. Download to temp directory
     *    c. Upload raw zip to MinIO: raw-logs/<batch_id>/<blob_name>/archive.zip
     *    d. Extract zip locally
     *    e. Classify each file (APP / ISTIO / UNKNOWN)
     *    f. Upload extracted files to MinIO: raw-logs/<batch_id>/<blob_name>/extracted/
     *    g. Track in blob_records table
     * 4. Update batch status
     */
    public BatchIngestionResult executeFull(UUID jobId, BlobIngestionConfig config) { ... }

    /**
     * Incremental ingestion:
     * 1. Get latest completed batch
     * 2. List current blobs
     * 3. Compare etags — only download new/changed blobs
     */
    public BatchIngestionResult executeIncremental(UUID jobId, BlobIngestionConfig config) { ... }
}
```

```java
public record BatchIngestionResult(
    UUID batchId,
    int totalBlobs,
    int downloadedBlobs,
    int skippedBlobs,
    int failedBlobs,
    long totalBytesDownloaded,
    Map<ZipExtractor.LogType, Integer> logTypeCounts
) {}
```

### 6.4 Dependencies

```kotlin
dependencies {
    implementation("com.azure:azure-storage-blob:12.29.0")
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

> **Secret:** Azure Storage credentials are provisioned from Azure Key Vault via the Dapr secret store (see Stage 29).

**Argo task resource class:** CPU (`cpupool` node selector)

---

## Testing & Verification Strategy

### Unit Tests

**`AzureBlobClientTest`** — validate blob listing, download, and property retrieval against Azurite.

```java
@SpringBootTest
@Testcontainers
class AzureBlobClientTest {

    @Container
    static final GenericContainer<?> AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.31.0")
            .withExposedPorts(10000)
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0");

    @Autowired
    AzureBlobClient azureBlobClient;

    @Test
    void listBlobs_returnsMatchingPrefix() {
        uploadTestBlob("logs/2024-01/app.zip", sampleZipBytes());
        uploadTestBlob("logs/2024-01/istio.zip", sampleZipBytes());
        uploadTestBlob("other/unrelated.txt", "ignored".getBytes());

        var blobs = azureBlobClient.listBlobs("test-container", "logs/2024-01/");

        assertThat(blobs).hasSize(2);
        assertThat(blobs).extracting(BlobItem::getName)
                .containsExactlyInAnyOrder("logs/2024-01/app.zip", "logs/2024-01/istio.zip");
    }

    @Test
    void downloadBlob_writesFileToTargetDirectory(@TempDir Path tempDir) {
        uploadTestBlob("logs/sample.zip", sampleZipBytes());

        Path downloaded = azureBlobClient.downloadBlob("test-container", "logs/sample.zip", tempDir);

        assertThat(downloaded).exists();
        assertThat(Files.size(downloaded)).isGreaterThan(0);
    }

    @Test
    void getBlobProperties_returnsEtagAndSize() {
        uploadTestBlob("logs/sample.zip", sampleZipBytes());

        BlobProperties props = azureBlobClient.getBlobProperties("test-container", "logs/sample.zip");

        assertThat(props.getETag()).isNotBlank();
        assertThat(props.getBlobSize()).isGreaterThan(0);
    }
}
```

**`ZipExtractorTest`** — verify extraction, format detection, and security protections.

```java
class ZipExtractorTest {

    private final ZipExtractor extractor = new ZipExtractor();

    @Test
    void extract_standardZip_extractsAllEntries(@TempDir Path tempDir) throws Exception {
        Path zipFile = createTestZip(tempDir, Map.of(
                "app-booking.log", "2024-01-15 INFO booking started",
                "app-payment.log", "2024-01-15 ERROR payment failed"));

        List<Path> extracted = extractor.extract(zipFile, tempDir.resolve("out"));

        assertThat(extracted).hasSize(2);
        assertThat(extracted).allSatisfy(p -> assertThat(p).exists());
    }

    @Test
    void extractAndClassify_zipFile_classifiesLogTypes(@TempDir Path tempDir) throws Exception {
        Path zipFile = createTestZip(tempDir, Map.of(
                "app-booking.log", "INFO log line",
                "istio-proxy.log", "envoy access log"));

        List<ZipExtractor.ClassifiedLogFile> classified =
                extractor.extractAndClassify(zipFile, tempDir.resolve("out"));

        assertThat(classified).extracting(ZipExtractor.ClassifiedLogFile::logType)
                .containsExactlyInAnyOrder(ZipExtractor.LogType.APP, ZipExtractor.LogType.ISTIO);
    }

    @Test
    void extract_pathTraversal_throwsSecurityException(@TempDir Path tempDir) throws Exception {
        Path maliciousZip = createZipWithEntry(tempDir, "../../../etc/passwd", "malicious");

        assertThatThrownBy(() -> extractor.extract(maliciousZip, tempDir.resolve("out")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("escapes target directory");
    }

    @Test
    void extract_zipBombExceedsRatio_throwsSecurityException(@TempDir Path tempDir) throws Exception {
        Path zipBomb = createHighCompressionZip(tempDir, 200); // 200:1 ratio

        assertThatThrownBy(() -> extractor.extract(zipBomb, tempDir.resolve("out")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("zip bomb");
    }

    @Test
    void extractAndClassify_gzipFile_extractsSingleFile(@TempDir Path tempDir) throws Exception {
        Path gzFile = createGzipFile(tempDir, "app-booking.log.gz", "INFO booking log line");

        List<ZipExtractor.ClassifiedLogFile> result =
                extractor.extractAndClassify(gzFile, tempDir.resolve("out"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).logType()).isEqualTo(ZipExtractor.LogType.APP);
    }

    @Test
    void extractAndClassify_tarGzArchive_extractsAllEntries(@TempDir Path tempDir) throws Exception {
        Path tarGz = createTarGz(tempDir, Map.of(
                "svc-a/app.log", "line 1", "svc-b/app.log", "line 2"));

        List<ZipExtractor.ClassifiedLogFile> result =
                extractor.extractAndClassify(tarGz, tempDir.resolve("out"));

        assertThat(result).hasSize(2);
    }

    @Test
    void extractAndClassify_plainTextFile_copiedDirectly(@TempDir Path tempDir) throws Exception {
        Path plainLog = tempDir.resolve("booking-service.log");
        Files.writeString(plainLog, "2024-01-15 INFO plain log line");

        List<ZipExtractor.ClassifiedLogFile> result =
                extractor.extractAndClassify(plainLog, tempDir.resolve("out"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sizeBytes()).isGreaterThan(0);
    }
}
```

**`BlobIngestionWorkerTest`** — unit test with mocked dependencies.

```java
@ExtendWith(MockitoExtension.class)
class BlobIngestionWorkerTest {

    @Mock AzureBlobClient azureBlobClient;
    @Mock ZipExtractor zipExtractor;
    @Mock MinioStorageClient minioClient;
    @Mock BlobBatchRepository batchRepository;
    @InjectMocks BlobIngestionWorker worker;

    @Test
    void executeFull_skipsAlreadyDownloadedBlobs() {
        var blob = testBlobItem("logs/app.zip", "etag-123");
        when(azureBlobClient.listBlobs(any(), any())).thenReturn(List.of(blob));
        when(batchRepository.existsByEtag("etag-123")).thenReturn(true);

        var result = worker.executeFull(UUID.randomUUID(), testConfig());

        assertThat(result.skippedBlobs()).isEqualTo(1);
        assertThat(result.downloadedBlobs()).isZero();
        verify(azureBlobClient, never()).downloadBlob(any(), any(), any());
    }

    @Test
    void executeIncremental_onlyDownloadsNewBlobs() {
        var existing = testBlobItem("logs/old.zip", "etag-old");
        var newBlob = testBlobItem("logs/new.zip", "etag-new");
        when(azureBlobClient.listBlobs(any(), any())).thenReturn(List.of(existing, newBlob));
        when(batchRepository.existsByEtag("etag-old")).thenReturn(true);
        when(batchRepository.existsByEtag("etag-new")).thenReturn(false);
        when(azureBlobClient.downloadBlob(any(), eq("logs/new.zip"), any()))
                .thenReturn(Path.of("/tmp/new.zip"));
        when(zipExtractor.extractAndClassify(any(), any())).thenReturn(List.of());

        var result = worker.executeIncremental(UUID.randomUUID(), testConfig());

        assertThat(result.downloadedBlobs()).isEqualTo(1);
        assertThat(result.skippedBlobs()).isEqualTo(1);
    }
}
```

### Integration Tests

**`BlobIngestionWorkerIntegrationTest`** — end-to-end with Azurite, MinIO, and PostgreSQL containers.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class BlobIngestionWorkerIntegrationTest {

    @Container
    static final GenericContainer<?> AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.31.0")
            .withExposedPorts(10000)
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-06-13T22-53-53Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flowforge_test")
            .withInitScript("db/migration/V001__init.sql");

    @Autowired BlobIngestionWorker worker;
    @Autowired BlobBatchRepository batchRepository;

    @Test
    void fullIngestion_downloadsExtractsAndTracksBlobs() {
        uploadToAzurite("test-container", "logs/2024-01/app.zip", sampleZipBytes());
        uploadToAzurite("test-container", "logs/2024-01/istio.zip", sampleZipBytes());

        var result = worker.executeFull(UUID.randomUUID(),
                new BlobIngestionConfig("test-container", "logs/2024-01/"));

        assertThat(result.downloadedBlobs()).isEqualTo(2);
        assertThat(result.failedBlobs()).isZero();
        assertThat(result.logTypeCounts()).containsKey(ZipExtractor.LogType.APP);
    }

    @Test
    void incrementalIngestion_skipsAlreadyProcessedBlobs() {
        uploadToAzurite("test-container", "logs/batch1.zip", sampleZipBytes());
        worker.executeFull(UUID.randomUUID(),
                new BlobIngestionConfig("test-container", "logs/"));

        uploadToAzurite("test-container", "logs/batch2.zip", sampleZipBytes());

        var result = worker.executeIncremental(UUID.randomUUID(),
                new BlobIngestionConfig("test-container", "logs/"));

        assertThat(result.downloadedBlobs()).isEqualTo(1);
        assertThat(result.skippedBlobs()).isEqualTo(1);
    }

    @Test
    void corruptZip_marksFailedAndContinues() {
        uploadToAzurite("test-container", "logs/good.zip", sampleZipBytes());
        uploadToAzurite("test-container", "logs/corrupt.zip", "not a zip".getBytes());

        var result = worker.executeFull(UUID.randomUUID(),
                new BlobIngestionConfig("test-container", "logs/"));

        assertThat(result.downloadedBlobs()).isEqualTo(1);
        assertThat(result.failedBlobs()).isEqualTo(1);
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Location | Description |
|---|---|---|
| `sample-app-logs.zip` | `src/test/resources/fixtures/` | Zip containing `app-booking.log` and `app-payment.log` with realistic Micronaut log lines |
| `sample-istio-logs.zip` | `src/test/resources/fixtures/` | Zip containing `istio-proxy.log` with Envoy access log entries |
| `sample-mixed.tar.gz` | `src/test/resources/fixtures/` | Tar.gz archive with app + Istio logs in subdirectories |
| `sample-plain.log` | `src/test/resources/fixtures/` | Uncompressed plain text log file |
| `path-traversal.zip` | `src/test/resources/fixtures/` | Zip with `../` entries for path traversal testing |
| `V001__init.sql` | `src/test/resources/db/migration/` | Schema migration for `blob_ingestion_batch` and `blob_records` tables |

Create helper methods in a shared `TestFixtures` class:

```java
class TestFixtures {
    static byte[] sampleZipBytes() { /* programmatically create a zip with sample log entries */ }
    static Path createTestZip(Path dir, Map<String, String> entries) { /* ... */ }
    static Path createTarGz(Path dir, Map<String, String> entries) { /* ... */ }
    static Path createGzipFile(Path dir, String filename, String content) { /* ... */ }
    static Path createZipWithEntry(Path dir, String entryName, String content) { /* ... */ }
    static Path createHighCompressionZip(Path dir, int ratio) { /* ... */ }
    static BlobItem testBlobItem(String name, String etag) { /* ... */ }
}
```

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `AzureBlobClient` | **Real** (Azurite container) in integration tests; **Mock** in worker unit tests | Azurite faithfully emulates Azure Blob Storage API |
| `ZipExtractor` | **Real** in all tests | Pure file-system logic — no external dependencies |
| `MinioStorageClient` | **Real** (MinIO container) in integration tests; **Mock** in unit tests | Integration tests validate actual object storage |
| `BlobBatchRepository` | **Real** (PostgreSQL container) in integration tests; **Mock** in unit tests | Unit tests need controlled etag lookup behavior |
| `BlobServiceClient` (Azure SDK) | **Never used directly** — `AzureBlobClient` wraps it | Azurite replaces it entirely in tests |

### CI/CD Considerations

- **JUnit 5 tags**: `@Tag("unit")` for pure unit tests, `@Tag("integration")` for Testcontainers-based tests.
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **Docker requirements**: CI runners must have Docker available for Azurite (`mcr.microsoft.com/azure-storage/azurite`), MinIO (`minio/minio`), and PostgreSQL (`postgres:16-alpine`) containers.
- **Temp directory cleanup**: All tests using `@TempDir` automatically clean up; Testcontainers handles container lifecycle.
- **Parallel execution**: Azurite container should use random ports (`withExposedPorts`) to allow parallel test suites.

## Verification

**Stage 6 sign-off requires all stages 1 through 6 to pass.** Run: `make verify`.

The verification report for stage 6 is `logs/stage-06.log`. It contains **cumulative output for stages 1–6** (Stage 1, then Stage 2, … then Stage 6 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| List blobs | Unit test with Azurite (test container) | Returns blob list |
| Download blob | Integration test | File downloaded |
| Zip extraction | Unit test: sample zip → extracted files | All files extracted |
| Log classification | app-*.log → APP, istio-*.log → ISTIO | Correct types |
| Full ingestion | Integration test: Azurite + MinIO + PostgreSQL | Batch tracked, files in MinIO |
| Idempotent re-run | Re-run same batch | Skips already-downloaded blobs |
| Incremental | Full → add blob → incremental | Only new blob downloaded |
| Error handling | Corrupt zip file | Marked FAILED, other blobs continue |
| Zip bomb protection | Zip with 100:1 ratio | SecurityException thrown |
| Path traversal | Zip with `../` entry names | SecurityException thrown |
| Gzip support | .gz log file | Extracted and classified |
| Tar.gz support | .tar.gz archive | All files extracted |
| Plain text | .log file (not compressed) | Copied and classified |

## Files to create

- `libs/ingest/build.gradle.kts`
- `libs/ingest/src/main/java/com/flowforge/ingest/blob/AzureBlobClient.java`
- `libs/ingest/src/main/java/com/flowforge/ingest/blob/ZipExtractor.java`
- `libs/ingest/src/main/java/com/flowforge/ingest/blob/BlobIngestionWorker.java`
- `libs/ingest/src/main/java/com/flowforge/ingest/blob/BatchIngestionResult.java`
- `libs/ingest/src/test/java/.../BlobIngestionWorkerIntegrationTest.java`

## Depends on

- Stage 01, 02, 03, 04

## Produces

- Zip blobs downloaded from Azure, extracted, classified, stored in MinIO
- Batch and record tracking in PostgreSQL
- Full and incremental ingestion modes
