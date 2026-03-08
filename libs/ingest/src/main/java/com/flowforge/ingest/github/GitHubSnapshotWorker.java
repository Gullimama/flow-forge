package com.flowforge.ingest.github;

import com.flowforge.common.entity.ParseArtifactEntity;
import com.flowforge.common.entity.ParseArtifactStatus;
import com.flowforge.common.model.SnapshotMetadata;
import com.flowforge.common.service.MetadataService;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.entity.SnapshotEntity;
import com.flowforge.common.entity.SnapshotStatus;
import com.flowforge.common.model.SnapshotMetadata.SnapshotType;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "flowforge.minio", name = "enabled", havingValue = "true", matchIfMissing = false)
public class GitHubSnapshotWorker {

    private static final Logger log = LoggerFactory.getLogger(GitHubSnapshotWorker.class);
    private static final String BUCKET_RAW_GIT = "raw-git";
    private static final String PREFIX_SOURCE = "source";
    private static final String PREFIX_MANIFESTS = "manifests";

    private final GitHubSnapshotClient gitClient;
    private final FileClassifier classifier;
    private final MinioStorageClient storage;
    private final MetadataService metadata;

    public GitHubSnapshotWorker(
        GitHubSnapshotClient gitClient,
        FileClassifier classifier,
        MinioStorageClient storage,
        MetadataService metadata) {
        this.gitClient = gitClient;
        this.classifier = classifier;
        this.storage = storage;
        this.metadata = metadata;
    }

    /**
     * Execute a baseline snapshot: clone, classify, upload to MinIO, track in PostgreSQL.
     */
    public SnapshotResult executeBaseline(UUID jobId, String repoUrl, String branch) throws Exception {
        String token = getTokenFromJob(jobId);
        UUID snapshotId = UUID.randomUUID();
        storage.ensureBuckets();

        Path tempDir = Files.createTempDirectory("flowforge-snapshot-");
        Path cloneDir = gitClient.cloneRepository(repoUrl, branch, token, tempDir);
        try {
            String commitSha = gitClient.getHeadCommitSha(repoUrl, branch, token);
            if (commitSha == null) {
                commitSha = "";
            }

            List<String> allPaths = new ArrayList<>();
            Map<FileClassifier.FileType, Integer> typeCounts = new EnumMap<>(FileClassifier.FileType.class);
            for (FileClassifier.FileType t : FileClassifier.FileType.values()) {
                typeCounts.put(t, 0);
            }
            Set<String> services = new HashSet<>();
            int[] javaCount = {0};
            int[] configCount = {0};
            int[] manifestCount = {0};

            Files.walkFileTree(cloneDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!Files.isRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = cloneDir.relativize(file);
                    String relativePath = relative.toString().replace('\\', '/');
                    if (relativePath.startsWith(".git/")) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        byte[] content = Files.readAllBytes(file);
                        FileClassifier.ClassifiedFile cf = classifier.classify(relativePath);
                        allPaths.add(relativePath);
                        typeCounts.merge(cf.fileType(), 1, Integer::sum);
                        if (cf.serviceName() != null && !cf.serviceName().isEmpty()) {
                            services.add(cf.serviceName());
                        }
                        switch (cf.fileType()) {
                            case JAVA_SOURCE -> javaCount[0]++;
                            case YAML_CONFIG, PROPERTIES_CONFIG -> configCount[0]++;
                            case K8S_MANIFEST, ISTIO_MANIFEST, HELM_CHART -> manifestCount[0]++;
                            default -> {}
                        }
                        String keyPrefix = isManifestType(cf.fileType())
                            ? snapshotId + "/" + PREFIX_MANIFESTS + "/"
                            : snapshotId + "/" + PREFIX_SOURCE + "/" + (cf.serviceName().isEmpty() ? "unknown" : cf.serviceName()) + "/";
                        String objectKey = keyPrefix + relativePath;
                        String contentType = contentType(relativePath);
                        storage.putObject(BUCKET_RAW_GIT, objectKey, content, contentType);
                        String contentHash = sha256Hex(content);
                        upsertParseArtifact(snapshotId, relativePath, cf.fileType(), objectKey, contentHash);
                    } catch (Exception e) {
                        log.warn("Failed to process file {}: {}", relativePath, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            SnapshotMetadata meta = new SnapshotMetadata(
                snapshotId, repoUrl, branch != null ? branch : "master", commitSha,
                SnapshotType.BASELINE, Instant.now(), allPaths);
            metadata.createSnapshot(meta);
            metadata.updateSnapshotStatus(snapshotId, SnapshotStatus.COMPLETED);

            return new SnapshotResult(
                snapshotId, commitSha, allPaths.size(), javaCount[0], configCount[0], manifestCount[0],
                new ArrayList<>(services), typeCounts);
        } finally {
            deleteRecursively(cloneDir);
            if (!cloneDir.equals(tempDir)) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * Execute a refresh snapshot: diff from latest, upload only changed files.
     */
    public SnapshotResult executeRefresh(UUID jobId) throws Exception {
        Optional<SnapshotEntity> latestOpt = metadata.getLatestSnapshot();
        if (latestOpt.isEmpty()) {
            throw new IllegalStateException("No baseline snapshot found; run a baseline first");
        }
        SnapshotEntity latest = latestOpt.get();
        String repoUrl = latest.getRepoUrl();
        String branch = latest.getBranch() != null ? latest.getBranch() : "master";
        String baseSha = latest.getCommitSha();
        if (baseSha == null || baseSha.isEmpty()) {
            throw new IllegalStateException("Latest snapshot has no commit SHA");
        }
        String token = getTokenFromJob(jobId);
        String headSha = gitClient.getHeadCommitSha(repoUrl, branch, token);
        if (headSha == null || headSha.equals(baseSha)) {
            return new SnapshotResult(
                latest.getSnapshotId(), headSha != null ? headSha : baseSha, 0, 0, 0, 0,
                List.of(), new EnumMap<>(FileClassifier.FileType.class));
        }
        List<String> changedPaths = gitClient.getChangedFiles(repoUrl, baseSha, headSha, token);
        if (changedPaths.isEmpty()) {
            return new SnapshotResult(
                latest.getSnapshotId(), headSha, 0, 0, 0, 0,
                List.of(), new EnumMap<>(FileClassifier.FileType.class));
        }

        UUID snapshotId = UUID.randomUUID();
        storage.ensureBuckets();
        SnapshotMetadata meta = new SnapshotMetadata(
            snapshotId, repoUrl, branch, headSha, SnapshotType.REFRESH, Instant.now(), changedPaths);
        metadata.createSnapshot(meta);
        metadata.updateSnapshotParent(snapshotId, latest.getSnapshotId());

        Path tempDir = Files.createTempDirectory("flowforge-refresh-");
        Path cloneDir = gitClient.cloneRepository(repoUrl, branch, token, tempDir);
        try {
            Map<FileClassifier.FileType, Integer> typeCounts = new EnumMap<>(FileClassifier.FileType.class);
            for (FileClassifier.FileType t : FileClassifier.FileType.values()) {
                typeCounts.put(t, 0);
            }
            Set<String> services = new HashSet<>();
            int javaCount = 0;
            int configCount = 0;
            int manifestCount = 0;

            for (String relativePath : changedPaths) {
                Path file = cloneDir.resolve(relativePath);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                byte[] content = Files.readAllBytes(file);
                FileClassifier.ClassifiedFile cf = classifier.classify(relativePath);
                typeCounts.merge(cf.fileType(), 1, Integer::sum);
                if (cf.serviceName() != null && !cf.serviceName().isEmpty()) {
                    services.add(cf.serviceName());
                }
                switch (cf.fileType()) {
                    case JAVA_SOURCE -> javaCount++;
                    case YAML_CONFIG, PROPERTIES_CONFIG -> configCount++;
                    case K8S_MANIFEST, ISTIO_MANIFEST, HELM_CHART -> manifestCount++;
                    default -> {}
                }
                String keyPrefix = isManifestType(cf.fileType())
                    ? snapshotId + "/" + PREFIX_MANIFESTS + "/"
                    : snapshotId + "/" + PREFIX_SOURCE + "/" + (cf.serviceName().isEmpty() ? "unknown" : cf.serviceName()) + "/";
                String objectKey = keyPrefix + relativePath;
                String contentType = contentType(relativePath);
                storage.putObject(BUCKET_RAW_GIT, objectKey, content, contentType);
                String contentHash = sha256Hex(content);
                upsertParseArtifact(snapshotId, relativePath, cf.fileType(), objectKey, contentHash);
            }

            metadata.updateSnapshotStatus(snapshotId, SnapshotStatus.COMPLETED);

            return new SnapshotResult(
                snapshotId, headSha, changedPaths.size(), javaCount, configCount, manifestCount,
                new ArrayList<>(services), typeCounts);
        } finally {
            deleteRecursively(cloneDir);
            if (!cloneDir.equals(tempDir)) {
                deleteRecursively(tempDir);
            }
        }
    }

    private String getTokenFromJob(UUID jobId) {
        return metadata.getJob(jobId)
            .map(j -> j.getInputParams())
            .filter(p -> p != null && p.containsKey("githubToken"))
            .map(p -> String.valueOf(p.get("githubToken")))
            .orElse("");
    }

    private static boolean isManifestType(FileClassifier.FileType t) {
        return t == FileClassifier.FileType.K8S_MANIFEST
            || t == FileClassifier.FileType.ISTIO_MANIFEST
            || t == FileClassifier.FileType.HELM_CHART;
    }

    private static String contentType(String path) {
        if (path.endsWith(".java")) return "text/x-java-source";
        if (path.endsWith(".yml") || path.endsWith(".yaml")) return "application/x-yaml";
        if (path.endsWith(".properties")) return "text/x-java-properties";
        if (path.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    private void upsertParseArtifact(UUID snapshotId, String artifactKey, FileClassifier.FileType fileType, String minioPath, String contentHash) {
        ParseArtifactEntity artifact = new ParseArtifactEntity();
        artifact.setSnapshotId(snapshotId);
        artifact.setArtifactType(fileType.name());
        artifact.setArtifactKey(artifactKey);
        artifact.setContentHash(contentHash);
        artifact.setMinioPath(BUCKET_RAW_GIT + "/" + minioPath);
        artifact.setStatus(ParseArtifactStatus.PENDING);
        artifact.setMetadata(Map.of());
        metadata.upsertParseArtifact(artifact);
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.exists(path)) {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws java.io.IOException {
                        Files.delete(f);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path d, java.io.IOException e) throws java.io.IOException {
                        if (e != null) throw e;
                        Files.delete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Failed to delete temp dir {}: {}", path, e.getMessage());
        }
    }
}
