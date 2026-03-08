package com.flowforge.ingest.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        if (tempRepo != null && Files.exists(tempRepo)) {
            deleteRecursively(tempRepo);
        }
    }

    @Test
    void cloneRepositoryCreatesLocalCopy() throws Exception {
        Path cloneDir = Files.createTempDirectory("clone");
        try {
            String uri = tempRepo.toUri().toString();
            Path result = client.cloneRepository(uri, "master", null, cloneDir);
            assertThat(result.resolve("README.md")).exists();
        } finally {
            if (Files.exists(cloneDir)) {
                deleteRecursively(cloneDir);
            }
        }
    }

    @Test
    void getHeadCommitShaReturnsValidSha() throws Exception {
        String uri = tempRepo.toUri().toString();
        String sha = client.getHeadCommitSha(uri, "master", null);
        assertThat(sha).hasSize(40);
        assertThat(sha).matches("[a-f0-9]{40}");
    }

    @Test
    void getChangedFilesDetectsNewCommit() throws Exception {
        String baseSha;
        try (Git git = Git.open(tempRepo.toFile())) {
            baseSha = git.log().setMaxCount(1).call().iterator().next().getName();
            Files.writeString(tempRepo.resolve("NewFile.java"), "class New {}");
            Files.writeString(tempRepo.resolve("Another.java"), "class Another {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add files").call();
        }
        String uri = tempRepo.toUri().toString();
        String headSha = client.getHeadCommitSha(uri, "master", null);
        List<String> changed = client.getChangedFiles(uri, baseSha, headSha, null);
        assertThat(changed).containsExactlyInAnyOrder("NewFile.java", "Another.java");
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path p : stream.toList()) {
                    deleteRecursively(p);
                }
            }
        }
        Files.delete(path);
    }
}
