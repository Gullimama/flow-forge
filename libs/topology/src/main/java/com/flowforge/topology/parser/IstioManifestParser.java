package com.flowforge.topology.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.flowforge.topology.model.TopologyEdge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parse Istio CRDs (VirtualService, DestinationRule, Gateway) as raw YAML.
 */
@Component
public class IstioManifestParser {

    private static final Logger log = LoggerFactory.getLogger(IstioManifestParser.class);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<TopologyEdge> parseIstioManifests(Path manifestDir) {
        List<TopologyEdge> edges = new ArrayList<>();
        if (!Files.isDirectory(manifestDir)) {
            return edges;
        }
        try (var stream = Files.walk(manifestDir)) {
            stream.filter(this::isYamlFile)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        JsonNode root = yamlMapper.readTree(content);
                        if (root == null) return;
                        String kind = root.path("kind").asText("");
                        switch (kind) {
                            case "VirtualService" -> parseVirtualService(root, edges);
                            case "DestinationRule" -> parseDestinationRule(root, edges);
                            case "Gateway" -> parseGateway(root, edges);
                            default -> { }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse Istio manifest: {}", file, e);
                    }
                });
        } catch (IOException e) {
            log.warn("Failed to walk Istio manifest directory", e);
        }
        return edges;
    }

    private void parseVirtualService(JsonNode root, List<TopologyEdge> edges) {
        String vsName = root.path("metadata").path("name").asText("");
        JsonNode http = root.path("spec").path("http");
        if (!http.isArray()) return;
        for (JsonNode rule : http) {
            JsonNode route = rule.path("route");
            if (!route.isArray()) continue;
            for (JsonNode r : route) {
                String dest = r.path("destination").path("host").asText("");
                if (!dest.isEmpty()) {
                    int weight = r.path("weight").asInt(100);
                    edges.add(new TopologyEdge(
                        "vs:" + vsName,
                        "svc:" + dest,
                        TopologyEdge.EdgeType.SERVICE_DEPENDENCY,
                        Map.of("source", "istio-virtualservice", "weight", String.valueOf(weight))
                    ));
                }
            }
        }
    }

    private void parseDestinationRule(JsonNode root, List<TopologyEdge> edges) {
        String drName = root.path("metadata").path("name").asText("");
        String host = root.path("spec").path("host").asText("");
        if (!host.isEmpty()) {
            edges.add(new TopologyEdge(
                "dr:" + drName,
                "svc:" + host,
                TopologyEdge.EdgeType.SERVICE_DEPENDENCY,
                Map.of("source", "istio-destinationrule")
            ));
        }
    }

    private void parseGateway(JsonNode root, List<TopologyEdge> edges) {
        String gwName = root.path("metadata").path("name").asText("");
        JsonNode servers = root.path("spec").path("servers");
        if (!servers.isArray()) return;
        for (JsonNode server : servers) {
            JsonNode hosts = server.path("hosts");
            if (!hosts.isArray()) continue;
            int port = server.path("port").path("number").asInt(0);
            for (JsonNode hostNode : hosts) {
                String host = hostNode.asText("");
                if (!host.isEmpty()) {
                    edges.add(new TopologyEdge(
                        "gw:" + gwName,
                        "host:" + host,
                        TopologyEdge.EdgeType.INGRESS_ROUTE,
                        Map.of("source", "istio-gateway", "port", String.valueOf(port))
                    ));
                }
            }
        }
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
