package com.flowforge.ingest.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowforge.common.entity.JobEntity;
import com.flowforge.common.entity.SnapshotEntity;
import com.flowforge.common.service.MetadataService;
import com.flowforge.common.client.MinioStorageClient;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitHubSnapshotWorkerUnitTest {

    @Mock private GitHubSnapshotClient gitClient;
    @Mock private FileClassifier classifier;
    @Mock private MinioStorageClient storage;
    @Mock private MetadataService metadata;

    private GitHubSnapshotWorker worker;

    @BeforeEach
    void setUp() {
        worker = new GitHubSnapshotWorker(gitClient, classifier, storage, metadata);
    }

    @Test
    void executeBaselineUploadsClassifiedFilesToMinio() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path fakeClone = Files.createTempDirectory("fake-clone");
        Files.writeString(fakeClone.resolve("App.java"), "class App {}");
        when(gitClient.cloneRepository(any(), any(), any(), any())).thenReturn(fakeClone);
        when(gitClient.getHeadCommitSha(any(), any(), any())).thenReturn("a".repeat(40));
        when(classifier.classify(any(String.class))).thenReturn(new FileClassifier.ClassifiedFile(
            "App.java", FileClassifier.FileType.JAVA_SOURCE, "api", "main"));
        JobEntity job = new JobEntity();
        job.setInputParams(java.util.Map.of("githubToken", ""));
        when(metadata.getJob(jobId)).thenReturn(Optional.of(job));

        SnapshotResult result = worker.executeBaseline(jobId, "https://github.com/org/repo", "master");

        assertThat(result.totalFiles()).isGreaterThanOrEqualTo(1);
        verify(storage, atLeastOnce()).putObject(eq("raw-git"), any(String.class), any(byte[].class), any(String.class));
        verify(metadata).createSnapshot(any());
        deleteRecursively(fakeClone);
    }

    @Test
    void executeRefreshOnlyUploadsChangedFiles() throws Exception {
        UUID jobId = UUID.randomUUID();
        var latestSnapshot = new SnapshotEntity();
        latestSnapshot.setSnapshotId(UUID.randomUUID());
        latestSnapshot.setCommitSha("b".repeat(40));
        latestSnapshot.setRepoUrl("file:///tmp/repo");
        latestSnapshot.setBranch("master");
        when(metadata.getLatestSnapshot()).thenReturn(Optional.of(latestSnapshot));
        when(gitClient.getHeadCommitSha(any(), any(), any())).thenReturn("c".repeat(40));
        when(gitClient.getChangedFiles(any(), eq("b".repeat(40)), eq("c".repeat(40)), any()))
            .thenReturn(java.util.List.of("Changed.java"));

        Path fakeClone = Files.createTempDirectory("fake-refresh");
        Files.writeString(fakeClone.resolve("Changed.java"), "class Changed {}");
        when(gitClient.cloneRepository(any(), any(), any(), any())).thenReturn(fakeClone);
        when(classifier.classify(any(String.class))).thenReturn(new FileClassifier.ClassifiedFile(
            "Changed.java", FileClassifier.FileType.JAVA_SOURCE, "api", "main"));
        JobEntity job2 = new JobEntity();
        job2.setInputParams(java.util.Map.of("githubToken", ""));
        when(metadata.getJob(jobId)).thenReturn(Optional.of(job2));

        SnapshotResult result = worker.executeRefresh(jobId);

        assertThat(result.totalFiles()).isEqualTo(1);
        verify(storage, atLeastOnce()).putObject(eq("raw-git"), org.mockito.ArgumentMatchers.argThat(key -> key != null && key.contains("Changed")), any(byte[].class), any(String.class));
        deleteRecursively(fakeClone);
    }

    private static void deleteRecursively(Path path) throws Exception {
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
    }
}
