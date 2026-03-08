package com.flowforge.topology.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.topology.model.TopologyEdge;
import com.flowforge.topology.model.TopologyNode;
import com.flowforge.topology.parser.IstioManifestParser;
import com.flowforge.topology.parser.KubernetesManifestParser;
import com.flowforge.topology.parser.MicronautConfigParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TopologyEnrichmentService {

    private final KubernetesManifestParser k8sParser;
    private final MicronautConfigParser configParser;
    private final IstioManifestParser istioParser;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TopologyEnrichmentService(
        KubernetesManifestParser k8sParser,
        MicronautConfigParser configParser,
        IstioManifestParser istioParser,
        OpenSearchClientWrapper openSearch,
        MinioStorageClient minio
    ) {
        this.k8sParser = k8sParser;
        this.configParser = configParser;
        this.istioParser = istioParser;
        this.openSearch = openSearch;
        this.minio = minio;
    }

    /**
     * Enrich a snapshot with topology: parse K8s manifests, Micronaut configs, Istio manifests, index to config-artifacts, store graph in MinIO.
     */
    public TopologyResult enrichSnapshot(UUID snapshotId, Path snapshotDir) {
        List<TopologyNode> nodes = new ArrayList<>();
        List<TopologyEdge> edges = new ArrayList<>();

        Path manifestDir = snapshotDir.resolve("k8s");
        if (Files.isDirectory(manifestDir)) {
            nodes.addAll(k8sParser.parseManifests(manifestDir));
        }

        Path configDir = snapshotDir.resolve("config");
        if (Files.isDirectory(configDir)) {
            try (var dirs = Files.list(configDir)) {
                dirs.filter(Files::isDirectory).forEach(serviceDir -> {
                    String serviceName = serviceDir.getFileName().toString();
                    Path appYml = serviceDir.resolve("application.yml");
                    if (Files.exists(appYml)) {
                        edges.addAll(configParser.parseServiceConfig(serviceName, appYml));
                    }
                });
            } catch (IOException e) {
                // ignore
            }
        }

        edges.addAll(inferEdgesFromCodeIndex(snapshotId));

        Path istioDir = snapshotDir.resolve("istio");
        if (Files.isDirectory(istioDir)) {
            edges.addAll(istioParser.parseIstioManifests(istioDir));
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        nodes.forEach(n -> documents.add(nodeToDocument(snapshotId, n)));
        edges.forEach(e -> documents.add(edgeToDocument(snapshotId, e)));

        if (!documents.isEmpty()) {
            try {
                openSearch.bulkIndex("config-artifacts", documents);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to bulk index config-artifacts", ex);
            }
        }

        TopologyGraph topology = new TopologyGraph(snapshotId, nodes, edges);
        minio.putJson("evidence", "topology/" + snapshotId + ".json", topology);

        return new TopologyResult(nodes.size(), edges.size());
    }

    /**
     * Infer edges from code-artifacts index (e.g. HTTP client / Kafka annotations). Stub: returns empty for now.
     */
    protected List<TopologyEdge> inferEdgesFromCodeIndex(UUID snapshotId) {
        return List.of();
    }

    private Map<String, Object> nodeToDocument(UUID snapshotId, TopologyNode node) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("snapshot_id", snapshotId.toString());
        doc.put("service_name", node.name());
        doc.put("file_path", "topology/node/" + node.id());
        doc.put("artifact_type", "topology-node");
        try {
            doc.put("content", objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            doc.put("content", node.toString());
        }
        doc.put("indexed_at", java.time.Instant.now().toString());
        return doc;
    }

    private Map<String, Object> edgeToDocument(UUID snapshotId, TopologyEdge edge) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("snapshot_id", snapshotId.toString());
        doc.put("service_name", edge.sourceId());
        doc.put("file_path", "topology/edge/" + edge.sourceId() + "-" + edge.targetId());
        doc.put("artifact_type", "topology-edge");
        try {
            doc.put("content", objectMapper.writeValueAsString(edge));
        } catch (JsonProcessingException e) {
            doc.put("content", edge.toString());
        }
        doc.put("indexed_at", java.time.Instant.now().toString());
        return doc;
    }

    public record TopologyGraph(UUID snapshotId, List<TopologyNode> nodes, List<TopologyEdge> edges) {}
    public record TopologyResult(int nodeCount, int edgeCount) {}
}
