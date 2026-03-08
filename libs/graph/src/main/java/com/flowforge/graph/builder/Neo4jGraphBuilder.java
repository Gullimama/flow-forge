package com.flowforge.graph.builder;

import com.flowforge.logparser.drain.DrainParser;
import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedField;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import com.flowforge.topology.model.TopologyEdge;
import com.flowforge.topology.model.TopologyNode;
import com.flowforge.topology.service.TopologyEnrichmentService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.TransactionWork;
import org.springframework.stereotype.Service;

@Service
public class Neo4jGraphBuilder {

    private final Driver driver;
    private final MeterRegistry meterRegistry;

    public Neo4jGraphBuilder(Driver driver, MeterRegistry meterRegistry) {
        this.driver = driver;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Build the full knowledge graph for a snapshot.
     */
    public GraphBuildResult buildGraph(
        UUID snapshotId,
        TopologyEnrichmentService.TopologyGraph topology,
        List<ParsedClass> classes,
        Map<String, DrainParser.LogCluster> logClusters
    ) {
        var counters = new GraphCounters();
        String snapshotIdStr = snapshotId.toString();

        try (Session session = driver.session()) {
            // 1. Create constraints + indexes
            createConstraintsAndIndexes(session);

            // 2. Create service nodes from topology
            session.executeWrite(tx -> {
                for (TopologyNode node : topology.nodes()) {
                    createTopologyNode(tx, snapshotIdStr, node);
                    counters.nodes.incrementAndGet();
                }
                return null;
            });

            // 3. Create class + method + endpoint nodes from parsed code
            session.executeWrite(tx -> {
                for (ParsedClass clazz : classes) {
                    createClassNode(tx, snapshotIdStr, clazz);
                    counters.nodes.incrementAndGet();

                    for (ParsedMethod method : clazz.methods()) {
                        createMethodNode(tx, snapshotIdStr, clazz.fqn(), method);
                        counters.nodes.incrementAndGet();

                        if (method.httpMethods() != null && !method.httpMethods().isEmpty()) {
                            createEndpointNode(tx, snapshotIdStr, clazz.fqn(), method);
                            counters.nodes.incrementAndGet();
                        }
                    }
                }
                return null;
            });

            // 4. Create log template nodes
            session.executeWrite(tx -> {
                for (DrainParser.LogCluster cluster : logClusters.values()) {
                    createLogTemplateNode(tx, snapshotIdStr, cluster);
                    counters.nodes.incrementAndGet();
                }
                return null;
            });

            // 5. Create relationships
            Map<String, String> idToLabel = new HashMap<>();
            Map<String, String> idToName = new HashMap<>();
            for (TopologyNode n : topology.nodes()) {
                idToLabel.put(n.id(), labelFor(n));
                idToName.put(n.id(), n.name());
            }

            String firstServiceName = topology.nodes().stream()
                .filter(n -> n instanceof TopologyNode.ServiceNode)
                .map(n -> ((TopologyNode.ServiceNode) n).name())
                .findFirst()
                .orElse(null);

            session.executeWrite(tx -> {
                for (TopologyEdge edge : topology.edges()) {
                    createTopologyEdge(tx, snapshotIdStr, edge, idToLabel, idToName);
                    counters.relationships.incrementAndGet();
                }

                for (ParsedClass clazz : classes) {
                    if (firstServiceName != null) {
                        linkClassToService(tx, snapshotIdStr, firstServiceName, clazz);
                        counters.relationships.incrementAndGet();
                    }

                    for (ParsedMethod method : clazz.methods()) {
                        tx.run("""
                            MATCH (c:Class {fqn: $classFqn, snapshotId: $snapshotId})
                            MERGE (m:Method {name: $methodName, classFqn: $classFqn, snapshotId: $snapshotId})
                            ON CREATE SET m.returnType = $returnType, m.isReactive = $isReactive,
                                          m.reactiveComplexity = $reactiveComplexity, m.lineStart = $lineStart
                            MERGE (c)-[:HAS_METHOD]->(m)
                            """,
                            Map.<String, Object>of(
                                "classFqn", clazz.fqn(),
                                "methodName", method.name(),
                                "snapshotId", snapshotIdStr,
                                "returnType", method.returnType() != null ? method.returnType() : "",
                                "isReactive", method.isReactive(),
                                "reactiveComplexity", method.reactiveComplexity() != null ? method.reactiveComplexity().name() : "NONE",
                                "lineStart", method.lineStart()
                            ));
                        counters.relationships.incrementAndGet();
                    }

                    for (ParsedField field : clazz.fields()) {
                        if (field.isInjected()) {
                            createInjectionEdge(tx, snapshotIdStr, clazz.fqn(), field);
                            counters.relationships.incrementAndGet();
                        }
                    }
                }
                return null;
            });
        }

        Counter nodesCounter = meterRegistry.counter("flowforge.graph.nodes.created");
        nodesCounter.increment(counters.nodes.get());
        Counter relsCounter = meterRegistry.counter("flowforge.graph.relationships.created");
        relsCounter.increment(counters.relationships.get());

        return new GraphBuildResult(counters.nodes.get(), counters.relationships.get());
    }

    private void createConstraintsAndIndexes(Session session) {
        session.executeWrite(tx -> {
            tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (s:Service) REQUIRE (s.name, s.snapshotId) IS UNIQUE");
            tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (c:Class) REQUIRE (c.fqn, c.snapshotId) IS UNIQUE");
            tx.run("CREATE INDEX IF NOT EXISTS FOR (m:Method) ON (m.classFqn, m.name)");
            tx.run("CREATE INDEX IF NOT EXISTS FOR (e:Endpoint) ON (e.httpMethod, e.httpPath)");
            tx.run("CREATE INDEX IF NOT EXISTS FOR (lt:LogTemplate) ON (lt.templateId)");
            return null;
        });
    }

    private void createTopologyNode(TransactionContext tx, String snapshotId, TopologyNode node) {
        switch (node) {
            case TopologyNode.ServiceNode s -> tx.run("""
                MERGE (n:Service {name: $name, snapshotId: $snapshotId})
                SET n.namespace = $namespace, n.image = $image, n.replicas = $replicas
                """,
                Map.of("name", s.name(), "snapshotId", snapshotId,
                    "namespace", s.namespace() != null ? s.namespace() : "default",
                    "image", s.image() != null ? s.image() : "",
                    "replicas", s.replicas()));

            case TopologyNode.KafkaTopicNode k -> tx.run("""
                MERGE (n:KafkaTopic {name: $name, snapshotId: $snapshotId})
                SET n.partitions = $partitions
                """,
                Map.of("name", k.name(), "snapshotId", snapshotId, "partitions", k.partitions()));

            case TopologyNode.DatabaseNode d -> tx.run("""
                MERGE (n:Database {name: $name, snapshotId: $snapshotId})
                SET n.dbType = $dbType, n.host = $host, n.port = $port
                """,
                Map.of("name", d.name(), "snapshotId", snapshotId,
                    "dbType", d.dbType() != null ? d.dbType() : "",
                    "host", d.host() != null ? d.host() : "",
                    "port", d.port()));

            case TopologyNode.IngressNode i -> tx.run("""
                MERGE (n:Ingress {name: $name, snapshotId: $snapshotId})
                """,
                Map.of("name", i.name(), "snapshotId", snapshotId));

            case TopologyNode.ConfigMapNode c -> tx.run("""
                MERGE (n:ConfigMap {name: $name, snapshotId: $snapshotId})
                """,
                Map.of("name", c.name(), "snapshotId", snapshotId));
        }
    }

    private void createClassNode(TransactionContext tx, String snapshotId, ParsedClass clazz) {
        tx.run("""
            MERGE (c:Class {fqn: $fqn, snapshotId: $snapshotId})
            SET c.simpleName = $simpleName, c.classType = $classType, c.filePath = $filePath
            """,
            Map.of("fqn", clazz.fqn(), "snapshotId", snapshotId,
                "simpleName", clazz.simpleName() != null ? clazz.simpleName() : "",
                "classType", clazz.classType() != null ? clazz.classType().name() : "CLASS",
                "filePath", clazz.filePath() != null ? clazz.filePath() : ""));
    }

    private void createMethodNode(TransactionContext tx, String snapshotId, String classFqn, ParsedMethod method) {
        tx.run("""
            MERGE (m:Method {name: $methodName, classFqn: $classFqn, snapshotId: $snapshotId})
            SET m.returnType = $returnType, m.isReactive = $isReactive,
                m.reactiveComplexity = $reactiveComplexity, m.lineStart = $lineStart
            """,
            Map.<String, Object>of(
                "methodName", method.name(),
                "classFqn", classFqn,
                "snapshotId", snapshotId,
                "returnType", method.returnType() != null ? method.returnType() : "",
                "isReactive", method.isReactive(),
                "reactiveComplexity", method.reactiveComplexity() != null ? method.reactiveComplexity().name() : "NONE",
                "lineStart", method.lineStart()
            ));
    }

    private void createEndpointNode(TransactionContext tx, String snapshotId, String classFqn, ParsedMethod method) {
        String path = method.httpPath() != null ? method.httpPath().orElse("") : "";
        List<String> methods = method.httpMethods() != null ? method.httpMethods() : List.of();
        for (String httpMethod : methods) {
            String fqn = classFqn + "#" + method.name();
            tx.run("""
                MERGE (e:Endpoint {httpMethod: $httpMethod, httpPath: $httpPath, snapshotId: $snapshotId})
                SET e.fqn = $fqn
                WITH e
                MATCH (m:Method {name: $methodName, classFqn: $classFqn, snapshotId: $snapshotId})
                MERGE (m)-[:EXPOSES_ENDPOINT]->(e)
                """,
                Map.of("httpMethod", httpMethod, "httpPath", path, "snapshotId", snapshotId,
                    "fqn", fqn, "methodName", method.name(), "classFqn", classFqn));
        }
    }

    private void createLogTemplateNode(TransactionContext tx, String snapshotId, DrainParser.LogCluster cluster) {
        String template = cluster.templateString();
        long matchCount = cluster.matchCount() != null ? cluster.matchCount().get() : 0L;
        tx.run("""
            MERGE (lt:LogTemplate {templateId: $templateId, snapshotId: $snapshotId})
            SET lt.template = $template, lt.matchCount = $matchCount
            """,
            Map.<String, Object>of("templateId", cluster.clusterId(), "snapshotId", snapshotId,
                "template", template, "matchCount", matchCount));
    }

    private static String labelFor(TopologyNode n) {
        return switch (n) {
            case TopologyNode.ServiceNode s -> "Service";
            case TopologyNode.KafkaTopicNode k -> "KafkaTopic";
            case TopologyNode.DatabaseNode d -> "Database";
            case TopologyNode.IngressNode i -> "Ingress";
            case TopologyNode.ConfigMapNode c -> "ConfigMap";
        };
    }

    private void createTopologyEdge(TransactionContext tx, String snapshotId, TopologyEdge edge,
                                    Map<String, String> idToLabel, Map<String, String> idToName) {
        String sourceLabel = idToLabel.get(edge.sourceId());
        String targetLabel = idToLabel.get(edge.targetId());
        String sourceName = idToName.get(edge.sourceId());
        String targetName = idToName.get(edge.targetId());
        if (sourceLabel == null || targetLabel == null || sourceName == null || targetName == null) {
            return;
        }
        String relType = relationshipTypeFor(edge.edgeType());
        if (relType == null) return;

        String url = edge.metadata() != null && edge.metadata().containsKey("url") ? edge.metadata().get("url") : "";
        String method = edge.metadata() != null && edge.metadata().containsKey("method") ? edge.metadata().get("method") : "";
        String jdbcUrl = edge.metadata() != null && edge.metadata().containsKey("jdbcUrl") ? edge.metadata().get("jdbcUrl") : "";

        String cypher = "MATCH (a:" + sourceLabel + " {name: $sourceName, snapshotId: $snapshotId}) " +
            "MATCH (b:" + targetLabel + " {name: $targetName, snapshotId: $snapshotId}) " +
            "MERGE (a)-[r:" + relType + "]->(b) " +
            (relType.equals("CALLS_HTTP") ? "SET r.url = $url, r.method = $method " : "") +
            (relType.equals("CONNECTS_TO") ? "SET r.jdbcUrl = $jdbcUrl " : "");

        Map<String, Object> params = new HashMap<>(Map.of(
            "sourceName", sourceName, "targetName", targetName, "snapshotId", snapshotId));
        if (relType.equals("CALLS_HTTP")) {
            params.put("url", url);
            params.put("method", method);
        }
        if (relType.equals("CONNECTS_TO")) {
            params.put("jdbcUrl", jdbcUrl);
        }
        tx.run(cypher, params);
    }

    private static String relationshipTypeFor(TopologyEdge.EdgeType edgeType) {
        return switch (edgeType) {
            case HTTP_CALL -> "CALLS_HTTP";
            case KAFKA_PRODUCE -> "PRODUCES_TO";
            case KAFKA_CONSUME -> "CONSUMES_FROM";
            case DATABASE_CONNECT -> "CONNECTS_TO";
            case CONFIG_REF -> "USES_CONFIG";
            case INGRESS_ROUTE -> "INGRESS_ROUTE";
            case SERVICE_DEPENDENCY -> "SERVICE_DEPENDENCY";
        };
    }

    private void linkClassToService(TransactionContext tx, String snapshotId, String serviceName, ParsedClass clazz) {
        tx.run("""
            MATCH (s:Service {name: $serviceName, snapshotId: $snapshotId})
            MATCH (c:Class {fqn: $fqn, snapshotId: $snapshotId})
            MERGE (s)-[:CONTAINS_CLASS]->(c)
            """,
            Map.of("serviceName", serviceName, "snapshotId", snapshotId, "fqn", clazz.fqn()));
    }

    private void createInjectionEdge(TransactionContext tx, String snapshotId, String classFqn, ParsedField field) {
        tx.run("""
            MATCH (c:Class {fqn: $classFqn, snapshotId: $snapshotId})
            MATCH (dep:Class {fqn: $depFqn, snapshotId: $snapshotId})
            MERGE (c)-[:INJECTS]->(dep)
            """,
            Map.of("classFqn", classFqn, "snapshotId", snapshotId, "depFqn", field.type()));
    }

    static class GraphCounters {
        final AtomicInteger nodes = new AtomicInteger(0);
        final AtomicInteger relationships = new AtomicInteger(0);
    }
}
