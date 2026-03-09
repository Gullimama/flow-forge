package com.flowforge.graph.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Path;
import org.springframework.stereotype.Service;

@Service
public class Neo4jGraphQueryService {

    private final Driver driver;

    public Neo4jGraphQueryService(Driver driver) {
        this.driver = driver;
    }

    /** One node in an HTTP or call chain (service name + optional edge method/url). */
    public record ChainNode(String serviceName, String httpMethod, String path, String methodName) {}

    /**
     * Find services that expose HTTP endpoints and have no incoming CALLS_HTTP (entry points).
     */
    public List<String> findEntryPointServices() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (s:Service)
                    WHERE EXISTS { (s)-[:CONTAINS_CLASS]->()-[:HAS_METHOD]->()-[:EXPOSES_ENDPOINT]->() }
                    AND NOT EXISTS { (caller:Service)-[:CALLS_HTTP]->(s) }
                    RETURN s.name AS name
                    """);
                return result.list(r -> r.get("name").asString(null));
            });
        }
    }

    /**
     * Get all call chains starting from the given service, up to maxDepth hops.
     */
    @SuppressWarnings("unchecked")
    public List<List<ChainNode>> getCallChainsFrom(String entryServiceName, int maxDepth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH path = (s:Service {name: $entry})-[r:CALLS_HTTP*1..$max]->(t:Service)
                    RETURN path
                    """,
                    Map.of("entry", entryServiceName, "max", (long) Math.max(1, Math.min(maxDepth, 10))));
                var chains = new ArrayList<List<ChainNode>>();
                for (Record record : result.list()) {
                    Path path = record.get("path").asPath();
                    List<org.neo4j.driver.types.Node> nodeList = new ArrayList<>();
                    path.nodes().forEach(nodeList::add);
                    List<org.neo4j.driver.types.Relationship> relList = new ArrayList<>();
                    path.relationships().forEach(relList::add);
                    var nodes = new ArrayList<ChainNode>();
                    for (int i = 0; i < nodeList.size(); i++) {
                        var node = nodeList.get(i);
                        String name = node.containsKey("name") ? node.get("name").asString() : "";
                        String method = null;
                        String url = null;
                        if (i > 0 && i - 1 < relList.size()) {
                            var rel = relList.get(i - 1);
                            if (rel.containsKey("method")) method = rel.get("method").asString(null);
                            if (rel.containsKey("url")) url = rel.get("url").asString(null);
                        }
                        nodes.add(new ChainNode(name, method, url, null));
                    }
                    if (!nodes.isEmpty()) chains.add(nodes);
                }
                return chains;
            });
        }
    }

    /**
     * Find Kafka topics with their producer and consumer services.
     */
    public List<Map<String, Object>> findKafkaTopicsWithConnections() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (t:KafkaTopic)
                    OPTIONAL MATCH (p:Service)-[:PRODUCES_TO]->(t)
                    OPTIONAL MATCH (t)<-[:CONSUMES_FROM]-(c:Service)
                    WITH t.name AS name,
                         collect(DISTINCT p.name) AS producers,
                         collect(DISTINCT c.name) AS consumers
                    WHERE size([x IN producers WHERE x IS NOT NULL]) > 0 OR size([x IN consumers WHERE x IS NOT NULL]) > 0
                    RETURN name, [x IN producers WHERE x IS NOT NULL] AS producers, [x IN consumers WHERE x IS NOT NULL] AS consumers
                    """);
                return result.list(Record::asMap);
            });
        }
    }

    /** Get all neighbors of a service within N hops. */
    public List<Map<String, Object>> getServiceNeighborhood(String serviceName, int hops) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH path = (s:Service {name: $name})-[*1..$hops]-(neighbor)
                    RETURN DISTINCT neighbor, type(last(relationships(path))) AS relType,
                           labels(neighbor) AS labels
                    """,
                    Map.of("name", serviceName, "hops", (long) hops));
                return result.list(Record::asMap);
            });
        }
    }

    /** Get the call chain from one service to another. */
    public List<Map<String, Object>> getCallChain(String fromService, String toService) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH path = shortestPath(
                        (a:Service {name: $from})-[*..10]->(b:Service {name: $to})
                    )
                    RETURN [n IN nodes(path) | {labels: labels(n), props: properties(n)}] AS nodes,
                           [r IN relationships(path) | type(r)] AS relationships
                    """,
                    Map.of("from", fromService, "to", toService));
                return result.list(Record::asMap);
            });
        }
    }

    /** Get all endpoints exposed by a service. */
    public List<Map<String, Object>> getServiceEndpoints(String serviceName) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (s:Service {name: $name})-[:CONTAINS_CLASS]->(c:Class)
                          -[:HAS_METHOD]->(m:Method)-[:EXPOSES_ENDPOINT]->(e:Endpoint)
                    RETURN e.httpMethod AS method, e.httpPath AS path,
                           c.fqn AS className, m.name AS methodName,
                           m.reactiveComplexity AS complexity
                    """,
                    Map.of("name", serviceName));
                return result.list(Record::asMap);
            });
        }
    }

    /** Search endpoints by query text (path or method name). */
    public List<Map<String, Object>> searchEndpoints(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (s:Service)-[:CONTAINS_CLASS]->(c:Class)
                          -[:HAS_METHOD]->(m:Method)-[:EXPOSES_ENDPOINT]->(e:Endpoint)
                    WHERE toLower(toString(e.httpPath)) CONTAINS toLower($q)
                       OR toLower(toString(e.httpMethod)) CONTAINS toLower($q)
                       OR toLower(m.name) CONTAINS toLower($q)
                    RETURN s.name AS service, e.httpMethod AS method, e.httpPath AS path,
                           c.fqn AS className, m.name AS methodName
                    LIMIT 50
                    """,
                    Map.of("q", query.trim()));
                return result.list(Record::asMap);
            });
        }
    }

    /**
     * Get all nodes for a snapshot for GNN graph preparation.
     * Returns maps with: id (elementId string), type (first label), degree, isReactive, reactiveComplexity.
     * Order is stable by elementId for consistent node indexing.
     */
    public List<Map<String, Object>> getAllNodes(UUID snapshotId) {
        if (snapshotId == null) {
            return List.of();
        }
        String sid = snapshotId.toString();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (n) WHERE n.snapshotId = $sid
                    OPTIONAL MATCH (n)-[r]-()
                    WITH n, labels(n) AS labels, count(r) AS degree
                    RETURN elementId(n) AS id,
                           labels[0] AS type,
                           degree,
                           (n.isReactive = true) AS isReactive,
                           coalesce(n.reactiveComplexity, 'NONE') AS reactiveComplexity
                    ORDER BY elementId(n)
                    """,
                    Map.of("sid", sid));
                return result.list(Record::asMap);
            });
        }
    }

    /**
     * Get all directed edges for a snapshot (source and target as elementId).
     * Used with getAllNodes to build GNN edge index by mapping id -> node index.
     */
    public List<Map<String, Object>> getAllEdges(UUID snapshotId) {
        if (snapshotId == null) {
            return List.of();
        }
        String sid = snapshotId.toString();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (a)-[r]->(b) WHERE a.snapshotId = $sid AND b.snapshotId = $sid
                    RETURN elementId(a) AS source, elementId(b) AS target
                    """,
                    Map.of("sid", sid));
                return result.list(Record::asMap);
            });
        }
    }

    /** Find all reactive complex methods across all services. */
    public List<Map<String, Object>> findComplexReactiveMethods() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (s:Service)-[:CONTAINS_CLASS]->(c:Class)
                          -[:HAS_METHOD]->(m:Method)
                    WHERE m.reactiveComplexity IN ['BRANCHING', 'COMPLEX']
                    RETURN s.name AS service, c.fqn AS className,
                           m.name AS method, m.reactiveComplexity AS complexity
                    ORDER BY m.reactiveComplexity DESC
                    """);
                return result.list(Record::asMap);
            });
        }
    }
}
