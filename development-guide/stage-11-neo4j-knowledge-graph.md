# Stage 11 — Neo4j Knowledge Graph

## Goal

Build a rich knowledge graph in Neo4j representing the entire microservice estate: services, classes, methods, endpoints, Kafka topics, database connections, config dependencies, and runtime log patterns. This graph powers the GraphRAG retrieval pipeline in later stages.

## Prerequisites

- Stage 08 (parsed code → classes, methods, annotations)
- Stage 09 (parsed logs → templates, trace context)
- Stage 10 (topology → nodes, edges)

## What to build

### 11.1 Neo4j deployment

> **Local dev:** A Neo4j container is defined in `docker/docker-compose.yml` (Browser :7474, Bolt :7687). Use it for local iteration only.

#### ArgoCD Application

`k8s/argocd/apps/neo4j.yaml`:
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: neo4j
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "2"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-infra
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
  sources:
    - repoURL: https://helm.neo4j.com/neo4j
      chart: neo4j
      targetRevision: 5.25.1
      helm:
        valueFiles:
          - $values/k8s/infrastructure/neo4j/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: main
      ref: values
```

#### Helm values

`k8s/infrastructure/neo4j/values.yaml`:
```yaml
neo4j:
  name: neo4j
  edition: community
  password:
    fromExistingSecret:
      name: neo4j-credentials
      key: NEO4J_AUTH

image:
  customImage: neo4j:5.25-community

config:
  server.memory.heap.initial_size: "4g"
  server.memory.heap.max_size: "4g"
  server.memory.pagecache.size: "2g"
  server.directories.plugins: /var/lib/neo4j/plugins
  server.security.procedures.unrestricted: "apoc.*"

apoc:
  enabled: true

volumes:
  data:
    mode: defaultStorageClass
    defaultStorageClass:
      requests:
        storage: 50Gi

resources:
  requests:
    cpu: "4"
    memory: 8Gi
  limits:
    cpu: "4"
    memory: 8Gi

nodeSelector:
  agentpool: cpupool

services:
  neo4j:
    spec:
      type: ClusterIP
      ports:
        - name: tcp-bolt
          protocol: TCP
          port: 7687
          targetPort: 7687
        - name: tcp-http
          protocol: TCP
          port: 7474
          targetPort: 7474
```

### 11.2 Node labels and relationships

**Node labels:**
| Label | Key properties |
|---|---|
| `:Service` | name, namespace, image, replicas |
| `:Class` | fqn, simpleName, classType, filePath |
| `:Method` | name, returnType, isReactive, reactiveComplexity, lineStart |
| `:Endpoint` | httpMethod, httpPath, fqn |
| `:KafkaTopic` | name, partitions |
| `:Database` | name, dbType, host |
| `:ConfigMap` | name |
| `:LogTemplate` | templateId, template, matchCount |
| `:AnomalyEpisode` | episodeId, severity, startTime, endTime |

**Relationship types:**
| Relationship | From → To | Properties |
|---|---|---|
| `CONTAINS_CLASS` | Service → Class | |
| `HAS_METHOD` | Class → Method | |
| `EXPOSES_ENDPOINT` | Method → Endpoint | |
| `CALLS_HTTP` | Service → Service | url, method |
| `PRODUCES_TO` | Service → KafkaTopic | |
| `CONSUMES_FROM` | KafkaTopic → Service | |
| `CONNECTS_TO` | Service → Database | jdbcUrl |
| `USES_CONFIG` | Service → ConfigMap | |
| `EMITS_LOG` | Service → LogTemplate | count |
| `HAS_ANOMALY` | Service → AnomalyEpisode | |
| `IMPLEMENTS` | Class → Class | (interface) |
| `EXTENDS` | Class → Class | (superclass) |
| `INJECTS` | Class → Class | (DI dependency) |
| `INVOKES` | Method → Method | (call graph) |

### 11.3 Graph builder service

```java
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
    public GraphBuildResult buildGraph(UUID snapshotId, TopologyGraph topology,
                                        List<ParsedClass> classes,
                                        Map<String, DrainParser.LogCluster> logClusters) {
        var counters = new GraphCounters();

        try (var session = driver.session()) {
            // 1. Create constraints + indexes
            createConstraintsAndIndexes(session);

            // 2. Create service nodes from topology
            session.executeWrite(tx -> {
                for (var node : topology.nodes()) {
                    createTopologyNode(tx, snapshotId, node);
                    counters.nodes.incrementAndGet();
                }
                return null;
            });

            // 3. Create class + method nodes from parsed code
            session.executeWrite(tx -> {
                for (var clazz : classes) {
                    createClassNode(tx, snapshotId, clazz);
                    counters.nodes.incrementAndGet();

                    for (var method : clazz.methods()) {
                        createMethodNode(tx, snapshotId, clazz.fqn(), method);
                        counters.nodes.incrementAndGet();

                        if (!method.httpMethods().isEmpty()) {
                            createEndpointNode(tx, snapshotId, clazz.fqn(), method);
                            counters.nodes.incrementAndGet();
                        }
                    }
                }
                return null;
            });

            // 4. Create log template nodes
            session.executeWrite(tx -> {
                for (var cluster : logClusters.values()) {
                    createLogTemplateNode(tx, snapshotId, cluster);
                    counters.nodes.incrementAndGet();
                }
                return null;
            });

            // 5. Create relationships
            session.executeWrite(tx -> {
                // Topology edges
                for (var edge : topology.edges()) {
                    createTopologyEdge(tx, snapshotId, edge);
                    counters.relationships.incrementAndGet();
                }

                // Class containment + method relationships
                for (var clazz : classes) {
                    linkClassToService(tx, snapshotId, clazz);
                    counters.relationships.incrementAndGet();

                    for (var method : clazz.methods()) {
                        tx.run("""
                            MATCH (c:Class {fqn: $classFqn, snapshotId: $snapshotId})
                            MERGE (m:Method {name: $methodName, classFqn: $classFqn, snapshotId: $snapshotId})
                            MERGE (c)-[:HAS_METHOD]->(m)
                            """,
                            Map.of("classFqn", clazz.fqn(),
                                   "methodName", method.name(),
                                   "snapshotId", snapshotId.toString()));
                        counters.relationships.incrementAndGet();
                    }

                    // Injection relationships
                    for (var field : clazz.fields()) {
                        if (field.isInjected()) {
                            createInjectionEdge(tx, snapshotId, clazz.fqn(), field);
                            counters.relationships.incrementAndGet();
                        }
                    }
                }
                return null;
            });
        }

        meterRegistry.counter("flowforge.graph.nodes.created").increment(counters.nodes.get());
        meterRegistry.counter("flowforge.graph.relationships.created").increment(counters.relationships.get());

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

    private void createTopologyNode(TransactionContext tx, UUID snapshotId, TopologyNode node) {
        switch (node) {
            case TopologyNode.ServiceNode s -> tx.run("""
                MERGE (n:Service {name: $name, snapshotId: $snapshotId})
                SET n.namespace = $namespace, n.image = $image, n.replicas = $replicas
                """,
                Map.of("name", s.name(), "snapshotId", snapshotId.toString(),
                       "namespace", s.namespace(), "image", s.image(),
                       "replicas", s.replicas()));

            case TopologyNode.KafkaTopicNode k -> tx.run("""
                MERGE (n:KafkaTopic {name: $name, snapshotId: $snapshotId})
                SET n.partitions = $partitions
                """,
                Map.of("name", k.name(), "snapshotId", snapshotId.toString(),
                       "partitions", k.partitions()));

            case TopologyNode.DatabaseNode d -> tx.run("""
                MERGE (n:Database {name: $name, snapshotId: $snapshotId})
                SET n.dbType = $dbType, n.host = $host, n.port = $port
                """,
                Map.of("name", d.name(), "snapshotId", snapshotId.toString(),
                       "dbType", d.dbType(), "host", d.host(), "port", d.port()));

            case TopologyNode.IngressNode i -> tx.run("""
                MERGE (n:Ingress {name: $name, snapshotId: $snapshotId})
                """,
                Map.of("name", i.name(), "snapshotId", snapshotId.toString()));

            case TopologyNode.ConfigMapNode c -> tx.run("""
                MERGE (n:ConfigMap {name: $name, snapshotId: $snapshotId})
                """,
                Map.of("name", c.name(), "snapshotId", snapshotId.toString()));
        }
    }
}

public record GraphBuildResult(int nodesCreated, int relationshipsCreated) {}

class GraphCounters {
    final AtomicInteger nodes = new AtomicInteger(0);
    final AtomicInteger relationships = new AtomicInteger(0);
}
```

### 11.4 Graph query service

```java
@Service
public class Neo4jGraphQueryService {

    private final Driver driver;

    /** Get all neighbors of a service within N hops. */
    public List<Map<String, Object>> getServiceNeighborhood(String serviceName, int hops) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH path = (s:Service {name: $name})-[*1..$hops]-(neighbor)
                    RETURN DISTINCT neighbor, type(last(relationships(path))) AS relType,
                           labels(neighbor) AS labels
                    """,
                    Map.of("name", serviceName, "hops", hops));
                return result.list(Record::asMap);
            });
        }
    }

    /** Get the call chain from one service to another. */
    public List<Map<String, Object>> getCallChain(String fromService, String toService) {
        try (var session = driver.session()) {
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
        try (var session = driver.session()) {
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
        try (var session = driver.session()) {
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
```

### 11.5 Dependencies

```kotlin
// libs/graph/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:code-parser"))
    implementation(project(":libs:log-parser"))
    implementation(project(":libs:topology"))
    implementation(libs.neo4j.java.driver)   // org.neo4j.driver:neo4j-java-driver:5.26.0
}
```

Add to version catalog:
```toml
[versions]
neo4j-driver = "5.26.0"

[libraries]
neo4j-java-driver = { module = "org.neo4j.driver:neo4j-java-driver", version.ref = "neo4j-driver" }
```

### 11.6 Neo4j configuration

```java
@Configuration
public class Neo4jConfig {

    @Bean
    public Driver neo4jDriver(FlowForgeProperties props) {
        return GraphDatabase.driver(
            props.neo4j().uri(),
            AuthTokens.basic(props.neo4j().username(), props.neo4j().password()),
            Config.builder()
                .withMaxConnectionPoolSize(50)
                .withConnectionAcquisitionTimeout(30, TimeUnit.SECONDS)
                .build()
        );
    }
}
```

## Testing & Verification Strategy

### Unit Tests

**`Neo4jGraphBuilderTest`** — tests the graph builder logic in isolation by mocking the Neo4j `Driver`, `Session`, and `TransactionContext`.

```java
@ExtendWith(MockitoExtension.class)
class Neo4jGraphBuilderTest {

    @Mock Driver driver;
    @Mock Session session;
    @Mock TransactionContext tx;
    @Mock MeterRegistry meterRegistry;
    @Mock Counter counter;

    @InjectMocks Neo4jGraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        when(driver.session()).thenReturn(session);
        when(meterRegistry.counter(anyString())).thenReturn(counter);
        when(session.executeWrite(any())).thenAnswer(invocation -> {
            TransactionWork<?> work = invocation.getArgument(0);
            return work.execute(tx);
        });
    }

    @Test
    void buildGraph_createsConstraintsBeforeNodes() {
        var topology = new TopologyGraph(List.of(
            new TopologyNode.ServiceNode("order-service", "default", "img:1.0", 2)
        ), List.of());

        graphBuilder.buildGraph(UUID.randomUUID(), topology, List.of(), Map.of());

        var inOrder = Mockito.inOrder(session);
        inOrder.verify(session, atLeastOnce()).executeWrite(any()); // constraints first
    }

    @Test
    void buildGraph_createsServiceNodeForEachTopologyNode() {
        var snapshotId = UUID.randomUUID();
        var topology = new TopologyGraph(List.of(
            new TopologyNode.ServiceNode("order-service", "default", "img:1.0", 2),
            new TopologyNode.ServiceNode("payment-service", "default", "img:2.0", 1)
        ), List.of());

        var result = graphBuilder.buildGraph(snapshotId, topology, List.of(), Map.of());

        assertThat(result.nodesCreated()).isEqualTo(2);
    }

    @Test
    void buildGraph_createsEndpointNodesOnlyForHttpMethods() {
        var method = new ParsedMethod("getBooking", "Mono<Booking>", true,
            ReactiveComplexity.SIMPLE, 10, List.of("GET"), List.of("/bookings/{id}"));
        var nonHttpMethod = new ParsedMethod("processInternal", "void", false,
            ReactiveComplexity.NONE, 20, List.of(), List.of());
        var clazz = new ParsedClass("com.example.BookingController", "BookingController",
            ClassType.REST_CONTROLLER, "BookingController.java",
            List.of(method, nonHttpMethod), List.of());

        var topology = new TopologyGraph(List.of(), List.of());
        var result = graphBuilder.buildGraph(UUID.randomUUID(), topology, List.of(clazz), Map.of());

        // 1 class + 2 methods + 1 endpoint (only the HTTP method)
        assertThat(result.nodesCreated()).isEqualTo(4);
    }

    @Test
    void buildGraph_createsInjectionEdgesForInjectedFields() {
        var field = new ParsedField("bookingRepo", "BookingRepository", true);
        var clazz = new ParsedClass("com.example.BookingService", "BookingService",
            ClassType.SERVICE, "BookingService.java", List.of(), List.of(field));

        var topology = new TopologyGraph(List.of(), List.of());
        graphBuilder.buildGraph(UUID.randomUUID(), topology, List.of(clazz), Map.of());

        verify(tx, atLeastOnce()).run(contains("INJECTS"), anyMap());
    }

    @Test
    void createTopologyNode_handlesAllSwitchCases() {
        var snapshotId = UUID.randomUUID();
        var nodes = List.<TopologyNode>of(
            new TopologyNode.ServiceNode("svc", "ns", "img", 1),
            new TopologyNode.KafkaTopicNode("topic-1", 3),
            new TopologyNode.DatabaseNode("db-1", "POSTGRES", "db-host", 5432),
            new TopologyNode.IngressNode("ingress-1"),
            new TopologyNode.ConfigMapNode("config-1")
        );
        var topology = new TopologyGraph(nodes, List.of());

        var result = graphBuilder.buildGraph(snapshotId, topology, List.of(), Map.of());

        assertThat(result.nodesCreated()).isEqualTo(5);
    }
}
```

**`Neo4jGraphQueryServiceTest`** — verifies Cypher query construction with a mocked session returning canned `Record` lists.

```java
@ExtendWith(MockitoExtension.class)
class Neo4jGraphQueryServiceTest {

    @Mock Driver driver;
    @Mock Session session;
    @InjectMocks Neo4jGraphQueryService queryService;

    @BeforeEach
    void setUp() {
        when(driver.session()).thenReturn(session);
    }

    @Test
    void getServiceNeighborhood_passesHopsParameter() {
        when(session.executeRead(any())).thenReturn(List.of());

        queryService.getServiceNeighborhood("order-service", 3);

        verify(session).executeRead(any());
    }

    @Test
    void getCallChain_returnsEmptyListWhenNoPathExists() {
        when(session.executeRead(any())).thenReturn(List.of());

        var result = queryService.getCallChain("svc-a", "svc-z");

        assertThat(result).isEmpty();
    }

    @Test
    void findComplexReactiveMethods_returnsOnlyBranchingAndComplex() {
        var row = Map.<String, Object>of(
            "service", "order-service",
            "className", "com.example.OrderHandler",
            "method", "processOrder",
            "complexity", "BRANCHING"
        );
        when(session.executeRead(any())).thenReturn(List.of(row));

        var result = queryService.findComplexReactiveMethods();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("complexity")).isEqualTo("BRANCHING");
    }
}
```

### Integration Tests

**`Neo4jGraphBuilderIntegrationTest`** — uses Testcontainers Neo4j to run against a real database.

```java
@Testcontainers
@Tag("integration")
class Neo4jGraphBuilderIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.25-community")
        .withAdminPassword("testpass")
        .withPlugins("apoc");

    private Driver driver;
    private Neo4jGraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        driver = GraphDatabase.driver(neo4j.getBoltUrl(),
            AuthTokens.basic("neo4j", "testpass"));
        graphBuilder = new Neo4jGraphBuilder(driver, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> { tx.run("MATCH (n) DETACH DELETE n"); return null; });
        }
        driver.close();
    }

    @Test
    void buildGraph_createsConstraintsInNeo4j() {
        var snapshotId = UUID.randomUUID();
        graphBuilder.buildGraph(snapshotId,
            new TopologyGraph(List.of(new TopologyNode.ServiceNode("svc", "ns", "img", 1)),
                List.of()),
            List.of(), Map.of());

        try (var session = driver.session()) {
            var constraints = session.run("SHOW CONSTRAINTS").list();
            assertThat(constraints).isNotEmpty();
        }
    }

    @Test
    void buildGraph_serviceNodesQueryableAfterBuild() {
        var snapshotId = UUID.randomUUID();
        var topology = new TopologyGraph(List.of(
            new TopologyNode.ServiceNode("order-service", "default", "img:1.0", 2),
            new TopologyNode.ServiceNode("payment-service", "default", "img:2.0", 1)
        ), List.of(new TopologyEdge("order-service", "payment-service", EdgeType.CALLS_HTTP,
            Map.of("url", "/pay"))));

        graphBuilder.buildGraph(snapshotId, topology, List.of(), Map.of());

        try (var session = driver.session()) {
            var count = session.run("MATCH (s:Service) RETURN count(s) AS cnt")
                .single().get("cnt").asInt();
            assertThat(count).isEqualTo(2);

            var edges = session.run("MATCH ()-[r:CALLS_HTTP]->() RETURN count(r) AS cnt")
                .single().get("cnt").asInt();
            assertThat(edges).isEqualTo(1);
        }
    }

    @Test
    void buildGraph_fullGraphWithClassesMethodsAndEndpoints() {
        var snapshotId = UUID.randomUUID();
        var method = new ParsedMethod("getBooking", "Mono<Booking>", true,
            ReactiveComplexity.SIMPLE, 10, List.of("GET"), List.of("/bookings/{id}"));
        var clazz = new ParsedClass("com.example.BookingController", "BookingController",
            ClassType.REST_CONTROLLER, "BookingController.java",
            List.of(method), List.of());
        var topology = new TopologyGraph(
            List.of(new TopologyNode.ServiceNode("booking-service", "default", "img", 1)),
            List.of());

        graphBuilder.buildGraph(snapshotId, topology, List.of(clazz), Map.of());

        try (var session = driver.session()) {
            var endpoints = session.run("MATCH (e:Endpoint) RETURN e.httpMethod AS m, e.httpPath AS p")
                .list();
            assertThat(endpoints).hasSize(1);
            assertThat(endpoints.getFirst().get("m").asString()).isEqualTo("GET");
        }
    }
}
```

**`Neo4jGraphQueryServiceIntegrationTest`** — seeds a small graph and runs each query method.

```java
@Testcontainers
@Tag("integration")
class Neo4jGraphQueryServiceIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.25-community")
        .withAdminPassword("testpass");

    private Driver driver;
    private Neo4jGraphQueryService queryService;

    @BeforeAll
    static void seedGraph(@Autowired Driver driver) {
        // Seed handled in @BeforeEach for isolation
    }

    @BeforeEach
    void setUp() {
        driver = GraphDatabase.driver(neo4j.getBoltUrl(),
            AuthTokens.basic("neo4j", "testpass"));
        queryService = new Neo4jGraphQueryService(driver);

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n) DETACH DELETE n");
                tx.run("""
                    CREATE (a:Service {name: 'api-gateway'})
                    CREATE (b:Service {name: 'order-service'})
                    CREATE (c:Service {name: 'payment-service'})
                    CREATE (a)-[:CALLS_HTTP]->(b)
                    CREATE (b)-[:CALLS_HTTP]->(c)
                    CREATE (b)-[:CONTAINS_CLASS]->(:Class {fqn: 'com.ex.OrderController'})
                        -[:HAS_METHOD]->(:Method {name: 'placeOrder', reactiveComplexity: 'BRANCHING'})
                        -[:EXPOSES_ENDPOINT]->(:Endpoint {httpMethod: 'POST', httpPath: '/orders'})
                    """);
                return null;
            });
        }
    }

    @AfterEach
    void tearDown() { driver.close(); }

    @Test
    void getServiceNeighborhood_returnsNeighborsWithinHops() {
        var result = queryService.getServiceNeighborhood("order-service", 1);
        var neighborNames = result.stream()
            .map(r -> ((org.neo4j.driver.types.Node) r.get("neighbor")).get("name").asString())
            .toList();
        assertThat(neighborNames).contains("api-gateway", "payment-service");
    }

    @Test
    void getCallChain_findsShortestPath() {
        var result = queryService.getCallChain("api-gateway", "payment-service");
        assertThat(result).isNotEmpty();
    }

    @Test
    void getServiceEndpoints_returnsHttpEndpoints() {
        var result = queryService.getServiceEndpoints("order-service");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("method")).isEqualTo("POST");
        assertThat(result.getFirst().get("path")).isEqualTo("/orders");
    }

    @Test
    void findComplexReactiveMethods_findsBranchingMethods() {
        var result = queryService.findComplexReactiveMethods();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("method")).isEqualTo("placeOrder");
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/topology-small.json` | 3 services, 2 Kafka topics, 1 database, 4 edges — minimal topology for builder tests |
| `src/test/resources/fixtures/parsed-classes.json` | 5 classes with methods, fields, annotations — covers REST controller, service, repository patterns |
| `src/test/resources/fixtures/log-clusters.json` | 10 log template clusters — used to test LogTemplate node creation |
| `src/test/resources/fixtures/expected-graph-counts.json` | Expected node/relationship counts for the small topology — assertion reference |

Sample data guidelines:
- Topology fixtures should include at least one of each `TopologyNode` variant (Service, KafkaTopic, Database, Ingress, ConfigMap) to exercise all switch cases.
- Parsed class fixtures should include at least one class with `@Autowired`/`@Inject` fields to test `INJECTS` edge creation.
- Include at least one reactive method with `reactiveComplexity = BRANCHING` or `COMPLEX` for `findComplexReactiveMethods` query coverage.

### Mocking Strategy

| Dependency | Unit tests | Integration tests |
|---|---|---|
| `Driver` / `Session` / `TransactionContext` | Mock (Mockito) — verify Cypher statements and parameter maps | Real (Testcontainers Neo4j) |
| `MeterRegistry` | Mock or `SimpleMeterRegistry` | `SimpleMeterRegistry` |
| `TopologyGraph`, `ParsedClass` | Construct in-memory from test fixtures | Same |
| `DrainParser.LogCluster` map | Construct in-memory with 2–3 sample clusters | Same |

- Never mock Neo4j in integration tests — use `org.testcontainers:testcontainers` with `Neo4jContainer`.
- For unit tests, mock at the `Driver.session()` boundary and verify that `TransactionContext.run()` receives the expected Cypher strings and parameter maps.

### CI/CD Considerations

- **JUnit tags:** Unit tests untagged (run in every build). Integration tests tagged `@Tag("integration")`.
- **Gradle filtering:**
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **Docker requirement:** Integration tests require Docker (Testcontainers Neo4j `neo4j:5.25-community`). CI runners must have Docker available.
- **Neo4j image caching:** Pull `neo4j:5.25-community` in the CI image build step to avoid pull latency during tests.
- **Test isolation:** Each test method cleans the graph via `MATCH (n) DETACH DELETE n` in `@BeforeEach` or `@AfterEach` to prevent cross-test contamination.
- **APOC plugin:** Integration tests needing APOC must use `.withPlugins("apoc")` on the container. Tests not requiring APOC should skip the plugin to speed up container startup.

## Verification

**Stage 11 sign-off requires all stages 1 through 11 to pass.** Run: `make verify`.

The verification report for stage 11 is `logs/stage-11.log`. It contains **cumulative output for stages 1–11** (Stage 1, then Stage 2, … then Stage 11 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| Neo4j starts | `kubectl get pods -n flowforge-infra -l app.kubernetes.io/name=neo4j` | Pod Running; ArgoCD app Synced/Healthy |
| Constraints | Run build → check constraints | Unique constraints exist |
| Service nodes | Build graph → MATCH (s:Service) | All services from topology |
| Class nodes | MATCH (c:Class) RETURN count(c) | Matches parsed class count |
| Method nodes | MATCH (m:Method) RETURN count(m) | Matches parsed method count |
| Endpoints | MATCH (e:Endpoint) | HTTP endpoints found |
| HTTP edges | MATCH ()-[:CALLS_HTTP]->() | Service-to-service calls |
| Kafka edges | MATCH ()-[:PRODUCES_TO\|CONSUMES_FROM]->() | Topic connections |
| Injection | MATCH ()-[:INJECTS]->() | DI relationships |
| Neighborhood | getServiceNeighborhood("booking-service", 2) | Returns 2-hop neighbors |
| Call chain | getCallChain("api-gateway", "payment-service") | Returns shortest path |
| Reactive query | findComplexReactiveMethods() | Lists BRANCHING/COMPLEX methods |
| Pattern matching | switch on TopologyNode variants | All 5 cases handled |

## Files to create

- `k8s/argocd/apps/neo4j.yaml`
- `k8s/infrastructure/neo4j/values.yaml`
- `libs/graph/build.gradle.kts`
- `libs/graph/src/main/java/com/flowforge/graph/config/Neo4jConfig.java`
- `libs/graph/src/main/java/com/flowforge/graph/builder/Neo4jGraphBuilder.java`
- `libs/graph/src/main/java/com/flowforge/graph/query/Neo4jGraphQueryService.java`
- `libs/graph/src/test/java/.../Neo4jGraphBuilderIntegrationTest.java` (Testcontainers Neo4j)
- `libs/graph/src/test/java/.../Neo4jGraphQueryServiceIntegrationTest.java`

## Depends on

- Stage 08 (parsed classes/methods)
- Stage 09 (log templates)
- Stage 10 (topology nodes/edges)

## Produces

- Knowledge graph in Neo4j with services, classes, methods, endpoints, topics, databases
- Typed query methods for graph traversal
- Foundation for GraphRAG retrieval (Stage 18)
