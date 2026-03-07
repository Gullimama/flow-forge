# Stage 10 — Topology Enrichment (Fabric8 Kubernetes Client)

## Goal

Parse Kubernetes manifests, Helm charts, and Micronaut configuration files to build a service topology graph. Capture Deployments, Services, ConfigMaps, Ingress rules, inter-service HTTP clients, Kafka topics, and database connections. Store enriched topology in OpenSearch `config-artifacts` index and prepare adjacency data for Neo4j (Stage 11).

## Prerequisites

- Stage 05 (snapshot with K8s manifests, Helm charts, config files)
- Stage 07 (OpenSearch — config-artifacts index)

## What to build

### 10.1 Topology model (records)

```java
public sealed interface TopologyNode {
    String id();
    String name();
    String nodeType();

    record ServiceNode(
        String id, String name, String namespace,
        String image, int replicas,
        Map<String, String> labels,
        List<ContainerPort> ports,
        ResourceLimits resources,
        List<EnvVar> envVars
    ) implements TopologyNode {
        public String nodeType() { return "SERVICE"; }
    }

    record IngressNode(
        String id, String name,
        List<IngressRule> rules
    ) implements TopologyNode {
        public String nodeType() { return "INGRESS"; }
    }

    record KafkaTopicNode(
        String id, String name,
        int partitions, int replicationFactor
    ) implements TopologyNode {
        public String nodeType() { return "KAFKA_TOPIC"; }
    }

    record DatabaseNode(
        String id, String name,
        String dbType, String host, int port
    ) implements TopologyNode {
        public String nodeType() { return "DATABASE"; }
    }

    record ConfigMapNode(
        String id, String name,
        Map<String, String> data
    ) implements TopologyNode {
        public String nodeType() { return "CONFIG_MAP"; }
    }
}

public record TopologyEdge(
    String sourceId,
    String targetId,
    EdgeType edgeType,
    Map<String, String> metadata
) {
    public enum EdgeType {
        HTTP_CALL, KAFKA_PRODUCE, KAFKA_CONSUME,
        DATABASE_CONNECT, CONFIG_REF, INGRESS_ROUTE,
        SERVICE_DEPENDENCY
    }
}

public record ContainerPort(String name, int port, String protocol) {}
public record ResourceLimits(String cpuRequest, String cpuLimit, String memRequest, String memLimit) {}
public record EnvVar(String name, String value, Optional<String> secretRef) {}
public record IngressRule(String host, String path, String serviceName, int servicePort) {}
```

### 10.2 Kubernetes manifest parser (Fabric8)

```java
@Component
public class KubernetesManifestParser {

    private final KubernetesClient kubeClient;

    public KubernetesManifestParser() {
        // Offline client — we parse files, not connect to a cluster
        this.kubeClient = new KubernetesClientBuilder()
            .withConfig(new ConfigBuilder().withAutoConfigure(false).build())
            .build();
    }

    /** Parse all K8s manifests in a directory. */
    public List<TopologyNode> parseManifests(Path manifestDir) {
        var nodes = new ArrayList<TopologyNode>();

        try (var files = Files.walk(manifestDir)) {
            files.filter(this::isYamlFile)
                .forEach(file -> {
                    try {
                        var resources = kubeClient.load(Files.newInputStream(file)).items();
                        for (var resource : resources) {
                            parseResource(resource).ifPresent(nodes::add);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse manifest: {}", file, e);
                    }
                });
        }
        return nodes;
    }

    private Optional<TopologyNode> parseResource(HasMetadata resource) {
        return switch (resource) {
            case Deployment d -> Optional.ofNullable(parseDeployment(d));
            case Service s -> Optional.of(parseService(s));
            case Ingress i -> Optional.of(parseIngress(i));
            case ConfigMap c -> Optional.of(parseConfigMap(c));
            default -> Optional.empty();
        };
    }

    private TopologyNode.ServiceNode parseDeployment(Deployment deployment) {
        var spec = deployment.getSpec();
        var podSpec = spec.getTemplate().getSpec();
        if (podSpec == null || podSpec.getContainers() == null || podSpec.getContainers().isEmpty()) {
            return null;
        }
        var container = podSpec.getContainers().get(0);
        var meta = deployment.getMetadata();
        var ports = container.getPorts() != null ? container.getPorts() : List.<io.fabric8.kubernetes.api.model.ContainerPort>of();
        return new TopologyNode.ServiceNode(
            "svc:" + meta.getName(),
            meta.getName(),
            meta.getNamespace() != null ? meta.getNamespace() : "default",
            container.getImage(),
            spec.getReplicas() != null ? spec.getReplicas() : 1,
            meta.getLabels() != null ? meta.getLabels() : Map.of(),
            ports.stream()
                .map(p -> new ContainerPort(p.getName(), p.getContainerPort(), p.getProtocol()))
                .toList(),
            extractResources(container),
            extractEnvVars(container)
        );
    }
}
```

### 10.3 Micronaut config parser

```java
@Component
public class MicronautConfigParser {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** Parse application.yml / bootstrap.yml for service dependencies. */
    public List<TopologyEdge> parseServiceConfig(String serviceName, Path configFile) {
        var edges = new ArrayList<TopologyEdge>();

        try {
            var config = yamlMapper.readTree(Files.readString(configFile));

            // HTTP client targets
            extractHttpClients(serviceName, config, edges);

            // Kafka topics
            extractKafkaConfig(serviceName, config, edges);

            // Database connections
            extractDatasources(serviceName, config, edges);

            // Redis connections
            extractRedisConfig(serviceName, config, edges);

        } catch (Exception e) {
            log.warn("Failed to parse config for {}: {}", serviceName, e.getMessage());
        }

        return edges;
    }

    private void extractHttpClients(String serviceName, JsonNode config, List<TopologyEdge> edges) {
        // Micronaut HTTP client config: micronaut.http.services.<name>.url
        var httpServices = config.at("/micronaut/http/services");
        if (!httpServices.isMissingNode()) {
            httpServices.fieldNames().forEachRemaining(targetService -> {
                edges.add(new TopologyEdge(
                    "svc:" + serviceName,
                    "svc:" + targetService,
                    TopologyEdge.EdgeType.HTTP_CALL,
                    Map.of("url", httpServices.get(targetService).path("url").asText(""))
                ));
            });
        }
    }

    private void extractKafkaConfig(String serviceName, JsonNode config, List<TopologyEdge> edges) {
        // Look for kafka.consumers and kafka.producers
        var consumers = config.at("/kafka/consumers");
        if (!consumers.isMissingNode()) {
            consumers.fieldNames().forEachRemaining(topic -> {
                edges.add(new TopologyEdge(
                    "topic:" + topic,
                    "svc:" + serviceName,
                    TopologyEdge.EdgeType.KAFKA_CONSUME,
                    Map.of()
                ));
            });
        }
    }

    private void extractDatasources(String serviceName, JsonNode config, List<TopologyEdge> edges) {
        var ds = config.at("/datasources/default/url");
        if (!ds.isMissingNode()) {
            var jdbcUrl = ds.asText();
            edges.add(new TopologyEdge(
                "svc:" + serviceName,
                "db:" + extractDbName(jdbcUrl),
                TopologyEdge.EdgeType.DATABASE_CONNECT,
                Map.of("jdbcUrl", jdbcUrl)
            ));
        }
    }
}
```

### 10.3b Istio manifest parser

```java
@Component
public class IstioManifestParser {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Parse Istio VirtualService, DestinationRule, and Gateway CRDs.
     * Fabric8 does not deserialize Istio CRDs natively; parse as raw YAML.
     */
    public List<TopologyEdge> parseIstioManifests(Path manifestDir) {
        var edges = new ArrayList<TopologyEdge>();
        try (var files = Files.walk(manifestDir)) {
            files.filter(this::isYamlFile).forEach(file -> {
                try {
                    var root = yamlMapper.readTree(Files.readString(file));
                    var kind = root.path("kind").asText("");
                    switch (kind) {
                        case "VirtualService" -> parseVirtualService(root, edges);
                        case "DestinationRule" -> parseDestinationRule(root, edges);
                        case "Gateway" -> parseGateway(root, edges);
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
        var hosts = root.path("spec").path("hosts");
        var http = root.path("spec").path("http");
        if (http.isArray()) {
            for (var rule : http) {
                for (var route : rule.path("route")) {
                    var dest = route.path("destination").path("host").asText("");
                    if (!dest.isEmpty() && hosts.isArray()) {
                        edges.add(new TopologyEdge(
                            "vs:" + root.path("metadata").path("name").asText(),
                            "svc:" + dest,
                            TopologyEdge.EdgeType.SERVICE_DEPENDENCY,
                            Map.of("source", "istio-virtualservice",
                                   "weight", String.valueOf(route.path("weight").asInt(100)))
                        ));
                    }
                }
            }
        }
    }

    private void parseDestinationRule(JsonNode root, List<TopologyEdge> edges) {
        var host = root.path("spec").path("host").asText("");
        if (!host.isEmpty()) {
            edges.add(new TopologyEdge(
                "dr:" + root.path("metadata").path("name").asText(),
                "svc:" + host,
                TopologyEdge.EdgeType.SERVICE_DEPENDENCY,
                Map.of("source", "istio-destinationrule")
            ));
        }
    }

    private void parseGateway(JsonNode root, List<TopologyEdge> edges) {
        var servers = root.path("spec").path("servers");
        if (servers.isArray()) {
            for (var server : servers) {
                var hosts = server.path("hosts");
                if (hosts.isArray()) {
                    for (var host : hosts) {
                        edges.add(new TopologyEdge(
                            "gw:" + root.path("metadata").path("name").asText(),
                            "host:" + host.asText(),
                            TopologyEdge.EdgeType.INGRESS_ROUTE,
                            Map.of("source", "istio-gateway",
                                   "port", String.valueOf(server.path("port").path("number").asInt()))
                        ));
                    }
                }
            }
        }
    }

    private boolean isYamlFile(Path path) {
        var name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
```

### 10.4 Topology enrichment service

```java
@Service
public class TopologyEnrichmentService {

    private final KubernetesManifestParser k8sParser;
    private final MicronautConfigParser configParser;
    private final IstioManifestParser istioParser;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;

    /**
     * Enrich a snapshot with topology information.
     */
    public TopologyResult enrichSnapshot(UUID snapshotId, Path snapshotDir) {
        // 1. Parse K8s manifests
        var manifestDir = snapshotDir.resolve("k8s");
        var nodes = k8sParser.parseManifests(manifestDir);

        // 2. Parse Micronaut configs for each service
        var edges = new ArrayList<TopologyEdge>();
        var configDir = snapshotDir.resolve("config");
        if (Files.isDirectory(configDir)) {
            try (var dirs = Files.list(configDir)) {
                dirs.filter(Files::isDirectory).forEach(serviceDir -> {
                    var serviceName = serviceDir.getFileName().toString();
                    var appYml = serviceDir.resolve("application.yml");
                    if (Files.exists(appYml)) {
                        edges.addAll(configParser.parseServiceConfig(serviceName, appYml));
                    }
                });
            }
        }

        // 3. Infer edges from code annotations (Kafka listeners, HTTP clients)
        edges.addAll(inferEdgesFromCodeIndex(snapshotId));

        // 3b. Parse Istio manifests (VirtualService, DestinationRule, Gateway)
        var istioDir = snapshotDir.resolve("istio");
        if (Files.isDirectory(istioDir)) {
            edges.addAll(istioParser.parseIstioManifests(istioDir));
        }

        // 4. Index to OpenSearch
        var documents = new ArrayList<Map<String, Object>>();
        nodes.forEach(n -> documents.add(nodeToDocument(snapshotId, n)));
        edges.forEach(e -> documents.add(edgeToDocument(snapshotId, e)));
        openSearch.bulkIndex("config-artifacts", documents);

        // 5. Store topology graph as evidence
        var topology = new TopologyGraph(snapshotId, nodes, edges);
        minio.putJson("evidence", "topology/" + snapshotId + ".json", topology);

        return new TopologyResult(nodes.size(), edges.size());
    }
}

public record TopologyGraph(UUID snapshotId, List<TopologyNode> nodes, List<TopologyEdge> edges) {}
public record TopologyResult(int nodeCount, int edgeCount) {}
```

### 10.5 Dependencies

```kotlin
// libs/topology/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.fabric8.kubernetes.client)    // io.fabric8:kubernetes-client:7.0.1
    implementation(libs.jackson.dataformat.yaml)       // for YAML parsing
    implementation(libs.snakeyaml)                     // transitive via Fabric8
}
```

Add to version catalog:
```toml
[versions]
fabric8 = "7.0.1"

[libraries]
fabric8-kubernetes-client = { module = "io.fabric8:kubernetes-client", version.ref = "fabric8" }
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| MinIO | `flowforge-minio.flowforge-infra.svc.cluster.local` | 9000 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |

> **Note:** Requires read access to the target Kubernetes cluster for the Fabric8 client. Service account RBAC is configured in Stage 28.

**Argo task resource class:** CPU (`cpupool` node selector)

---

## Testing & Verification Strategy

### Unit Tests

**`KubernetesManifestParserTest`** — validate K8s resource parsing using Fabric8 offline client.

```java
class KubernetesManifestParserTest {

    private final KubernetesManifestParser parser = new KubernetesManifestParser();

    @Test
    void parseManifests_deployment_extractsServiceNode(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment.yaml", manifestDir.resolve("booking-deployment.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0)).isInstanceOf(TopologyNode.ServiceNode.class);
        var svc = (TopologyNode.ServiceNode) nodes.get(0);
        assertThat(svc.name()).isEqualTo("booking-service");
        assertThat(svc.namespace()).isEqualTo("production");
        assertThat(svc.replicas()).isEqualTo(3);
        assertThat(svc.image()).contains("booking-service");
    }

    @Test
    void parseManifests_deployment_extractsPortsAndResources(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment.yaml", manifestDir.resolve("booking-deployment.yaml"));

        var svc = (TopologyNode.ServiceNode) parser.parseManifests(manifestDir).get(0);

        assertThat(svc.ports()).isNotEmpty();
        assertThat(svc.ports().get(0).port()).isEqualTo(8080);
        assertThat(svc.resources().cpuLimit()).isNotBlank();
        assertThat(svc.resources().memLimit()).isNotBlank();
    }

    @Test
    void parseManifests_ingress_extractsRules(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-ingress.yaml", manifestDir.resolve("ingress.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).filteredOn(n -> n instanceof TopologyNode.IngressNode).hasSize(1);
        var ingress = (TopologyNode.IngressNode) nodes.stream()
                .filter(n -> n instanceof TopologyNode.IngressNode).findFirst().orElseThrow();
        assertThat(ingress.rules()).isNotEmpty();
        assertThat(ingress.rules().get(0).host()).isNotBlank();
    }

    @Test
    void parseManifests_configMap_extractsData(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-configmap.yaml", manifestDir.resolve("config.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).filteredOn(n -> n instanceof TopologyNode.ConfigMapNode).hasSize(1);
        var cm = (TopologyNode.ConfigMapNode) nodes.stream()
                .filter(n -> n instanceof TopologyNode.ConfigMapNode).findFirst().orElseThrow();
        assertThat(cm.data()).isNotEmpty();
    }

    @Test
    void parseManifests_sealedInterfaceSwitch_dispatchesCorrectly(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment.yaml", manifestDir.resolve("deploy.yaml"));
        copyFixture("sample-ingress.yaml", manifestDir.resolve("ingress.yaml"));
        copyFixture("sample-configmap.yaml", manifestDir.resolve("cm.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).hasSize(3);
        assertThat(nodes.stream().map(TopologyNode::nodeType).toList())
                .containsExactlyInAnyOrder("SERVICE", "INGRESS", "CONFIG_MAP");
    }

    @Test
    void parseManifests_envVarsWithSecretRefs_captured(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment-with-secrets.yaml", manifestDir.resolve("deploy.yaml"));

        var svc = (TopologyNode.ServiceNode) parser.parseManifests(manifestDir).get(0);

        assertThat(svc.envVars()).isNotEmpty();
        assertThat(svc.envVars()).anyMatch(env -> env.secretRef().isPresent());
    }

    @Test
    void parseManifests_malformedYaml_skipsWithWarning(@TempDir Path manifestDir) throws Exception {
        Files.writeString(manifestDir.resolve("broken.yaml"), "{{not valid yaml at all");
        copyFixture("sample-deployment.yaml", manifestDir.resolve("good.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).hasSize(1);
    }
}
```

**`MicronautConfigParserTest`** — verify edge extraction from `application.yml`.

```java
class MicronautConfigParserTest {

    private final MicronautConfigParser parser = new MicronautConfigParser();

    @Test
    void parseServiceConfig_httpClients_createsHttpCallEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-application.yml", dir.resolve("application.yml"));

        var edges = parser.parseServiceConfig("booking-service",
                dir.resolve("application.yml"));

        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.HTTP_CALL)
                .isNotEmpty();
        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.HTTP_CALL)
                .extracting(TopologyEdge::targetId)
                .contains("svc:payment-service");
    }

    @Test
    void parseServiceConfig_kafkaConsumers_createsConsumeEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-application.yml", dir.resolve("application.yml"));

        var edges = parser.parseServiceConfig("booking-service",
                dir.resolve("application.yml"));

        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.KAFKA_CONSUME)
                .isNotEmpty();
    }

    @Test
    void parseServiceConfig_datasource_createsDatabaseEdge(@TempDir Path dir) throws Exception {
        copyFixture("sample-application.yml", dir.resolve("application.yml"));

        var edges = parser.parseServiceConfig("booking-service",
                dir.resolve("application.yml"));

        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.DATABASE_CONNECT)
                .hasSize(1);
        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.DATABASE_CONNECT)
                .extracting(TopologyEdge::targetId)
                .allMatch(id -> id.startsWith("db:"));
    }

    @Test
    void parseServiceConfig_missingConfig_returnsEmptyEdges(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "micronaut:\n  application:\n    name: empty-svc\n");

        var edges = parser.parseServiceConfig("empty-svc",
                dir.resolve("application.yml"));

        assertThat(edges).isEmpty();
    }
}
```

**`IstioManifestParserTest`** — verify Istio CRD parsing for VirtualService, DestinationRule, and Gateway.

```java
class IstioManifestParserTest {

    private final IstioManifestParser parser = new IstioManifestParser();

    @Test
    void parseIstioManifests_virtualService_createsServiceEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-virtualservice.yaml", dir.resolve("vs.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).isNotEmpty();
        assertThat(edges).allMatch(e ->
                e.edgeType() == TopologyEdge.EdgeType.SERVICE_DEPENDENCY);
        assertThat(edges).extracting(e -> e.metadata().get("source"))
                .contains("istio-virtualservice");
    }

    @Test
    void parseIstioManifests_virtualServiceWithWeights_capturesWeight(@TempDir Path dir) throws Exception {
        copyFixture("sample-virtualservice-canary.yaml", dir.resolve("vs-canary.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).anyMatch(e -> e.metadata().containsKey("weight"));
    }

    @Test
    void parseIstioManifests_destinationRule_createsEdge(@TempDir Path dir) throws Exception {
        copyFixture("sample-destinationrule.yaml", dir.resolve("dr.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).metadata().get("source")).isEqualTo("istio-destinationrule");
    }

    @Test
    void parseIstioManifests_gateway_createsIngressRouteEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-gateway.yaml", dir.resolve("gw.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).isNotEmpty();
        assertThat(edges).allMatch(e -> e.edgeType() == TopologyEdge.EdgeType.INGRESS_ROUTE);
        assertThat(edges).extracting(TopologyEdge::targetId)
                .allMatch(id -> id.startsWith("host:"));
    }

    @Test
    void parseIstioManifests_mixedManifests_parsesAll(@TempDir Path dir) throws Exception {
        copyFixture("sample-virtualservice.yaml", dir.resolve("vs.yaml"));
        copyFixture("sample-destinationrule.yaml", dir.resolve("dr.yaml"));
        copyFixture("sample-gateway.yaml", dir.resolve("gw.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges.size()).isGreaterThanOrEqualTo(3);
    }
}
```

### Integration Tests

**`TopologyEnrichmentServiceIntegrationTest`** — end-to-end: parse manifests + configs → build topology → index to OpenSearch + store in MinIO.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class TopologyEnrichmentServiceIntegrationTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH =
            new OpensearchContainer<>("opensearchproject/opensearch:2.18.0")
                    .withSecurityEnabled(false);

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-06-13T22-53-53Z");

    @Autowired TopologyEnrichmentService enrichmentService;
    @Autowired OpenSearchClientWrapper openSearch;
    @Autowired MinioStorageClient minio;

    @Test
    void enrichSnapshot_fullTopology_indexesNodesAndEdges(@TempDir Path snapshotDir) throws Exception {
        setupSnapshotFixtures(snapshotDir);

        var result = enrichmentService.enrichSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.nodeCount()).isGreaterThan(0);
        assertThat(result.edgeCount()).isGreaterThan(0);
        refreshIndex("config-artifacts");
        assertThat(openSearch.getDocCount("config-artifacts"))
                .isEqualTo(result.nodeCount() + result.edgeCount());
    }

    @Test
    void enrichSnapshot_storesToplogyGraphInMinio(@TempDir Path snapshotDir) throws Exception {
        setupSnapshotFixtures(snapshotDir);
        var snapshotId = UUID.randomUUID();

        enrichmentService.enrichSnapshot(snapshotId, snapshotDir);

        var json = minio.getJson("evidence", "topology/" + snapshotId + ".json");
        assertThat(json).isNotBlank();
        assertThat(json).contains("nodes");
        assertThat(json).contains("edges");
    }

    @Test
    void enrichSnapshot_istioManifests_addServiceDependencyEdges(@TempDir Path snapshotDir)
            throws Exception {
        setupSnapshotFixtures(snapshotDir);
        copyFixture("sample-virtualservice.yaml",
                snapshotDir.resolve("istio/booking-vs.yaml"));

        var result = enrichmentService.enrichSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.edgeCount()).isGreaterThan(0);
    }

    private void setupSnapshotFixtures(Path snapshotDir) throws Exception {
        var k8sDir = Files.createDirectories(snapshotDir.resolve("k8s"));
        copyFixture("sample-deployment.yaml", k8sDir.resolve("booking-deployment.yaml"));
        copyFixture("sample-ingress.yaml", k8sDir.resolve("ingress.yaml"));

        var configDir = Files.createDirectories(
                snapshotDir.resolve("config/booking-service"));
        copyFixture("sample-application.yml", configDir.resolve("application.yml"));

        Files.createDirectories(snapshotDir.resolve("istio"));
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Location | Description |
|---|---|---|
| `sample-deployment.yaml` | `src/test/resources/` | K8s Deployment for `booking-service`: 3 replicas, port 8080, resource limits, env vars |
| `sample-deployment-with-secrets.yaml` | `src/test/resources/` | Deployment with `envFrom` referencing K8s Secrets |
| `sample-ingress.yaml` | `src/test/resources/` | K8s Ingress with host rules and path-based routing |
| `sample-configmap.yaml` | `src/test/resources/` | ConfigMap with application configuration data |
| `sample-application.yml` | `src/test/resources/` | Micronaut `application.yml` with HTTP client targets, Kafka consumers, and datasource URL |
| `sample-virtualservice.yaml` | `src/test/resources/` | Istio VirtualService routing to `booking-service` with match rules |
| `sample-virtualservice-canary.yaml` | `src/test/resources/` | Istio VirtualService with weighted canary routing (80/20 split) |
| `sample-destinationrule.yaml` | `src/test/resources/` | Istio DestinationRule with circuit breaker and connection pool settings |
| `sample-gateway.yaml` | `src/test/resources/` | Istio Gateway with TLS configuration and host bindings |

Example `sample-application.yml` fixture:

```yaml
micronaut:
  application:
    name: booking-service
  http:
    services:
      payment-service:
        url: http://payment-service:8080
      inventory-service:
        url: http://inventory-service:8080
datasources:
  default:
    url: jdbc:postgresql://postgres:5432/bookings
kafka:
  consumers:
    booking-events:
      group-id: booking-consumer
```

Example `sample-deployment.yaml` fixture:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: booking-service
  namespace: production
  labels:
    app: booking-service
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: booking-service
          image: registry.example.com/booking-service:1.2.3
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: "1"
              memory: 1Gi
          env:
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: booking-secrets
                  key: db-password
```

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `KubernetesManifestParser` (Fabric8) | **Real** (offline mode) | Fabric8 client runs in offline mode — no cluster connection needed; parses YAML files locally |
| `MicronautConfigParser` | **Real** | Pure YAML parsing with Jackson; no external dependencies |
| `IstioManifestParser` | **Real** | Pure YAML parsing; Istio CRDs are parsed as raw `JsonNode` |
| `OpenSearchClientWrapper` | **Real** (Testcontainer) in integration tests; **Mock** in `TopologyEnrichmentService` unit tests | Isolate topology logic from indexing |
| `MinioStorageClient` | **Real** (Testcontainer) in integration tests; **Mock** in unit tests | Only needed for evidence persistence verification |
| `KubernetesClient` (Fabric8) | **Never mocked** — configured with `withAutoConfigure(false)` for offline parsing | Fabric8 in offline mode is essentially a YAML deserializer |

### CI/CD Considerations

- **JUnit 5 tags**: `@Tag("unit")` for `KubernetesManifestParserTest`, `MicronautConfigParserTest`, `IstioManifestParserTest` (zero containers needed); `@Tag("integration")` for `TopologyEnrichmentServiceIntegrationTest`.
- **Zero-container unit tests**: All three parsers operate on local YAML files with no cluster or container dependencies. Unit tests execute in <3 seconds.
- **Docker requirements**: Integration tests need `opensearchproject/opensearch:2.18.0` and `minio/minio` containers.
- **Fabric8 offline**: Ensure tests do **not** accidentally connect to a live K8s cluster. The `ConfigBuilder().withAutoConfigure(false)` is critical.
- **YAML fixtures**: Store all sample manifests in `src/test/resources/`. Validate that fixture files are well-formed YAML in CI with a pre-test linting step if desired.
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **Istio CRD coverage**: As new Istio CRD types are supported (e.g., `ServiceEntry`, `AuthorizationPolicy`), add corresponding fixture files and parser tests.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Parse Deployment | Feed K8s Deployment YAML | ServiceNode with image, replicas, ports |
| Parse Ingress | Feed Ingress YAML | IngressNode with rules |
| Parse ConfigMap | Feed ConfigMap YAML | ConfigMapNode with data |
| Pattern matching | Feed Deployment + Service | Correct switch dispatch |
| HTTP client edges | application.yml with http.services | HTTP_CALL edges created |
| Kafka edges | application.yml with kafka config | KAFKA_PRODUCE/CONSUME edges |
| Database edges | JDBC URL in datasources | DATABASE_CONNECT edge |
| Env var extraction | Deployment with env/envFrom | EnvVars with secret refs |
| Resource limits | Deployment with resources | CPU/memory captured |
| Multi-file parse | Directory with 20 YAMLs | All parsed, errors logged |
| Config-artifacts index | Full enrichment run | Nodes + edges in OpenSearch |
| Topology graph | Check MinIO evidence | JSON graph with nodes + edges |

## Files to create

- `libs/topology/build.gradle.kts`
- `libs/topology/src/main/java/com/flowforge/topology/model/TopologyNode.java`
- `libs/topology/src/main/java/com/flowforge/topology/model/TopologyEdge.java`
- `libs/topology/src/main/java/com/flowforge/topology/parser/KubernetesManifestParser.java`
- `libs/topology/src/main/java/com/flowforge/topology/parser/MicronautConfigParser.java`
- `libs/topology/src/main/java/com/flowforge/topology/service/TopologyEnrichmentService.java`
- `libs/topology/src/test/java/.../KubernetesManifestParserTest.java`
- `libs/topology/src/test/java/.../MicronautConfigParserTest.java`
- `libs/topology/src/test/java/.../TopologyEnrichmentServiceIntegrationTest.java`
- `libs/topology/src/test/resources/sample-deployment.yaml`
- `libs/topology/src/test/resources/sample-application.yml`

## Depends on

- Stage 05 (snapshot files)
- Stage 07 (OpenSearch config-artifacts index)

## Produces

- Service topology graph (nodes + edges) in MinIO evidence bucket
- Config artifacts indexed in OpenSearch `config-artifacts` index
- Adjacency data ready for Neo4j graph builder (Stage 11)
