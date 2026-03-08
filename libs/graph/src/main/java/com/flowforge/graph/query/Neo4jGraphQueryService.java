package com.flowforge.graph.query;

import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

@Service
public class Neo4jGraphQueryService {

    private final Driver driver;

    public Neo4jGraphQueryService(Driver driver) {
        this.driver = driver;
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
