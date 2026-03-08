package com.flowforge.logparser.drain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Java implementation of the Drain log parsing algorithm.
 * Fixed-depth parse tree for O(1) parsing per log line; similarity threshold for cluster matching; thread-safe.
 */
public class DrainParser {

    private final double similarityThreshold;
    private final int maxDepth;
    private final int maxChildren;
    private final ConcurrentHashMap<String, LogCluster> clusters = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock treeLock = new ReentrantReadWriteLock();
    private final DrainTree tree;
    private final AtomicLong clusterIdSeq = new AtomicLong(0);

    public DrainParser(double similarityThreshold, int maxDepth, int maxChildren) {
        this.similarityThreshold = similarityThreshold;
        this.maxDepth = maxDepth;
        this.maxChildren = maxChildren;
        this.tree = new DrainTree();
    }

    public record LogCluster(
        String clusterId,
        List<String> templateTokens,
        AtomicLong matchCount
    ) {
        public String templateString() {
            return String.join(" ", templateTokens);
        }
    }

    public LogCluster parse(String message) {
        List<String> tokens = preprocess(message);
        if (tokens.isEmpty()) {
            return createNewCluster(tokens);
        }

        treeLock.readLock().lock();
        LogCluster match;
        try {
            match = treeSearch(tokens);
            if (match != null && similarity(match.templateTokens(), tokens) >= similarityThreshold) {
                match.matchCount().incrementAndGet();
                return match;
            }
        } finally {
            treeLock.readLock().unlock();
        }

        treeLock.writeLock().lock();
        try {
            match = treeSearch(tokens);
            if (match != null && similarity(match.templateTokens(), tokens) >= similarityThreshold) {
                updateTemplate(match, tokens);
                match.matchCount().incrementAndGet();
                return match;
            }
            LogCluster cluster = createNewCluster(tokens);
            addToTree(cluster);
            clusters.put(cluster.clusterId(), cluster);
            return cluster;
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    private LogCluster createNewCluster(List<String> tokens) {
        String id = "cluster_" + clusterIdSeq.incrementAndGet();
        return new LogCluster(id, new ArrayList<>(tokens), new AtomicLong(1));
    }

    List<String> preprocess(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        return Arrays.stream(message.trim().split("\\s+"))
            .map(this::maskToken)
            .toList();
    }

    String maskToken(String token) {
        if (token.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return "<IP>";
        if (token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return "<UUID>";
        if (token.matches("\\d+")) return "<NUM>";
        if (token.matches("/[\\w/.\\-]+")) return "<PATH>";
        return token;
    }

    private LogCluster treeSearch(List<String> tokens) {
        List<String> prefix = prefix(tokens);
        return tree.findCluster(prefix, tokens, similarityThreshold);
    }

    private void addToTree(LogCluster cluster) {
        List<String> prefix = prefix(cluster.templateTokens());
        tree.addCluster(prefix, cluster, maxChildren);
    }

    private List<String> prefix(List<String> tokens) {
        int len = Math.min(maxDepth, tokens.size());
        return len == 0 ? List.of("") : tokens.subList(0, len);
    }

    void updateTemplate(LogCluster cluster, List<String> tokens) {
        List<String> t = cluster.templateTokens();
        for (int i = 0; i < Math.max(t.size(), tokens.size()); i++) {
            if (i >= t.size()) {
                t.add("<*>");
            } else if (i >= tokens.size()) {
                t.set(i, "<*>");
            } else if (!t.get(i).equals(tokens.get(i))) {
                t.set(i, "<*>");
            }
        }
    }

    static double similarity(List<String> template, List<String> tokens) {
        if (template.isEmpty() && tokens.isEmpty()) return 1.0;
        if (template.isEmpty() || tokens.isEmpty()) return 0.0;
        int matches = 0;
        int maxLen = Math.max(template.size(), tokens.size());
        for (int i = 0; i < maxLen; i++) {
            String a = i < template.size() ? template.get(i) : "<*>";
            String b = i < tokens.size() ? tokens.get(i) : "<*>";
            if (a.equals(b) || "<*>".equals(a) || "<*>".equals(b)) {
                matches++;
            }
        }
        return (double) matches / maxLen;
    }

    public Map<String, LogCluster> getClusters() {
        return Collections.unmodifiableMap(clusters);
    }

    /** Fixed-depth tree: each level keyed by token; leaf node holds LEAF_KEY -> list of clusters. */
    private static final class DrainTree {
        private static final String LEAF_KEY = "*";
        private final Map<String, Object> root = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        LogCluster findCluster(List<String> prefix, List<String> fullTokens, double threshold) {
            Map<String, Object> current = root;
            for (String key : prefix) {
                Object next = current.get(key);
                if (next == null) next = current.get("<*>");
                if (next == null) return null;
                current = (Map<String, Object>) next;
            }
            Object leaf = current.get(LEAF_KEY);
            if (leaf instanceof List<?> list && !list.isEmpty()) {
                for (Object o : list) {
                    if (o instanceof LogCluster c && similarity(c.templateTokens(), fullTokens) >= threshold) {
                        return c;
                    }
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        void addCluster(List<String> prefix, LogCluster cluster, int maxChildren) {
            Map<String, Object> current = root;
            for (int i = 0; i < prefix.size(); i++) {
                String key = prefix.get(i);
                if (i == prefix.size() - 1) {
                    Map<String, Object> leafNode = (Map<String, Object>) current.computeIfAbsent(key, k -> new ConcurrentHashMap<String, Object>());
                    List<LogCluster> list = (List<LogCluster>) leafNode.computeIfAbsent(LEAF_KEY, k -> new ArrayList<LogCluster>());
                    if (list.size() < maxChildren) {
                        list.add(cluster);
                    }
                    return;
                }
                current = (Map<String, Object>) current.computeIfAbsent(key, k -> new ConcurrentHashMap<String, Object>());
            }
        }
    }
}
