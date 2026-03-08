package com.flowforge.ingest.github;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

@Component
public class GitHubSnapshotClient {

    /**
     * Clone the repository at a specific branch/commit.
     *
     * @param repoUrl   repository URL (file:// or https://)
     * @param branch    branch name (e.g. master, main)
     * @param token     optional token for private repos (null for file://)
     * @param targetDir directory to clone into (must exist and be empty)
     * @return path to the cloned repository root
     */
    public Path cloneRepository(String repoUrl, String branch, String token, Path targetDir) throws Exception {
        var cloneCommand = Git.cloneRepository()
            .setURI(repoUrl)
            .setBranch(branch != null && !branch.isEmpty() ? branch : "master")
            .setDirectory(targetDir.toFile())
            .setCloneAllBranches(false);
        if (token != null && !token.isEmpty() && (repoUrl.startsWith("https://") || repoUrl.startsWith("http://"))) {
            cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""));
        }
        try (Git git = cloneCommand.call()) {
            return targetDir;
        }
    }

    /**
     * Get HEAD commit SHA for a branch. For file:// URLs, opens the local repo.
     */
    public String getHeadCommitSha(String repoUrl, String branch, String token) throws Exception {
        Path repoPath = toLocalPath(repoUrl);
        try (Git git = Git.open(repoPath.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? head.name() : null;
        }
    }

    /**
     * Get list of changed file paths between two commit SHAs (relative to repo root).
     * Requires repoUrl to be a local path (file://) so the repo can be opened.
     */
    public List<String> getChangedFiles(String repoUrl, String baseSha, String headSha, String token) throws Exception {
        Path repoPath = toLocalPath(repoUrl);
        Set<String> changed = new HashSet<>();
        try (Repository repo = Git.open(repoPath.toFile()).getRepository();
             RevWalk rw = new RevWalk(repo);
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            ObjectId baseId = repo.resolve(baseSha);
            ObjectId headId = repo.resolve(headSha);
            if (baseId == null || headId == null) {
                return new ArrayList<>();
            }
            RevCommit baseCommit = rw.parseCommit(baseId);
            RevCommit headCommit = rw.parseCommit(headId);
            List<DiffEntry> entries = df.scan(baseCommit.getTree(), headCommit.getTree());
            for (DiffEntry e : entries) {
                if (!DiffEntry.DEV_NULL.equals(e.getNewPath())) {
                    changed.add(e.getNewPath());
                }
                if (!DiffEntry.DEV_NULL.equals(e.getOldPath()) && !e.getOldPath().equals(e.getNewPath())) {
                    changed.add(e.getOldPath());
                }
            }
        }
        return new ArrayList<>(changed);
    }

    private static Path toLocalPath(String repoUrl) {
        if (repoUrl == null) {
            throw new IllegalArgumentException("repoUrl must not be null");
        }
        if (repoUrl.startsWith("file://")) {
            return Path.of(repoUrl.substring(7).replace("%20", " ")).toAbsolutePath();
        }
        throw new UnsupportedOperationException("Local repo path (file://) required for getHeadCommitSha/getChangedFiles");
    }
}
