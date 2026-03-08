package com.flowforge.topology.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.flowforge.topology.model.TopologyEdge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parse Micronaut application.yml for HTTP clients, Kafka, datasources, Redis.
 */
@Component
public class MicronautConfigParser {

    private static final Logger log = LoggerFactory.getLogger(MicronautConfigParser.class);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<TopologyEdge> parseServiceConfig(String serviceName, Path configFile) {
        List<TopologyEdge> edges = new ArrayList<>();
        if (!Files.isRegularFile(configFile)) {
            return edges;
        }
        try {
            String content = Files.readString(configFile);
            JsonNode config = yamlMapper.readTree(content);
            if (config == null) return edges;

            extractHttpClients(serviceName, config, edges);
            extractKafkaConfig(serviceName, config, edges);
            extractDatasources(serviceName, config, edges);
            extractRedisConfig(serviceName, config, edges);
        } catch (Exception e) {
            log.warn("Failed to parse config for {}: {}", serviceName, e.getMessage());
        }
        return edges;
    }

    private void extractHttpClients(String serviceName, JsonNode config, List<TopologyEdge> edges) {
        JsonNode httpServices = config.at("/micronaut/http/services");
        if (httpServices.isMissingNode() || !httpServices.isObject()) return;
        httpServices.fieldNames().forEachRemaining(targetService -> {
            JsonNode svc = httpServices.get(targetService);
            String url = svc != null && svc.has("url") ? svc.get("url").asText("") : "";
            edges.add(new TopologyEdge(
                "svc:" + serviceName,
                "svc:" + targetService,
                TopologyEdge.EdgeType.HTTP_CALL,
                Map.of("url", url)
            ));
        });
    }

    private void extractKafkaConfig(String serviceName, JsonNode config, List<TopologyEdge> edges) {
        JsonNode consumers = config.at("/kafka/consumers");
        if (!consumers.isMissingNode() && consumers.isObject()) {
            consumers.fieldNames().forEachRemaining(topic -> {
                edges.add(new TopologyEdge(
                    "topic:" + topic,
                    "svc:" + serviceName,
                    TopologyEdge.EdgeType.KAFKA_CONSUME,
                    Map.of()
                ));
            });
        }
        JsonNode producers = config.at("/kafka/producers");
        if (!producers.isMissingNode() && producers.isObject()) {
            producers.fieldNames().forEachRemaining(topic -> {
                edges.add(new TopologyEdge(
                    "svc:" + serviceName,
                    "topic:" + topic,
                    TopologyEdge.EdgeType.KAFKA_PRODUCE,
                    Map.of()
                ));
            });
        }
    }

    private void extractDatasources(String serviceName, JsonNode config, List<TopologyEdge> edges) {
        JsonNode ds = config.at("/datasources/default/url");
        if (ds.isMissingNode()) {
            ds = config.at("/datasource/url");
        }
        if (!ds.isMissingNode()) {
            String jdbcUrl = ds.asText("");
            if (!jdbcUrl.isEmpty()) {
                edges.add(new TopologyEdge(
                    "svc:" + serviceName,
                    "db:" + extractDbName(jdbcUrl),
                    TopologyEdge.EdgeType.DATABASE_CONNECT,
                    Map.of("jdbcUrl", jdbcUrl)
                ));
            }
        }
    }

    private void extractRedisConfig(String serviceName, JsonNode config, List<TopologyEdge> edges) {
        JsonNode redis = config.at("/redis/uri");
        if (redis.isMissingNode()) {
            redis = config.at("/redis/host");
        }
        if (!redis.isMissingNode()) {
            String target = "redis:" + redis.asText("redis");
            edges.add(new TopologyEdge(
                "svc:" + serviceName,
                target,
                TopologyEdge.EdgeType.DATABASE_CONNECT,
                Map.of("type", "redis")
            ));
        }
    }

    private String extractDbName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) return "unknown";
        int lastSlash = jdbcUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < jdbcUrl.length() - 1) {
            String rest = jdbcUrl.substring(lastSlash + 1);
            int q = rest.indexOf('?');
            return q > 0 ? rest.substring(0, q) : rest;
        }
        int colon = jdbcUrl.indexOf(':', jdbcUrl.indexOf("://") + 3);
        if (colon > 0) return jdbcUrl.substring(colon + 1);
        return "unknown";
    }
}
