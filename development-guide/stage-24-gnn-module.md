# Stage 24 — GNN Module (DJL + ONNX Runtime)

## Goal

Build a **Graph Neural Network** module using **DJL (Deep Java Library)** with ONNX Runtime backend for service interaction prediction and flow pattern recognition. The GNN operates on the Neo4j knowledge graph to predict missing edges, classify interaction patterns, and score flow completeness.

> **Why DJL?** DJL (Deep Java Library) is Amazon's framework-agnostic deep learning library for Java. It supports PyTorch, TensorFlow, and ONNX Runtime backends. For FlowForge, we train GNN models in Python (PyTorch Geometric), export to ONNX, and run inference in Java via DJL's ONNX Runtime engine — zero Python runtime dependencies.

## Prerequisites

- Stage 11 (Neo4j knowledge graph)

## What to build

### 24.1 Graph data preparation

```java
@Component
public class GraphDataPreparer {

    private final Neo4jGraphQueryService graphQuery;

    /**
     * Extract graph structure from Neo4j into tensor format for GNN.
     */
    public GraphData prepareGraphData(UUID snapshotId) {
        // 1. Extract all nodes with features
        var nodes = graphQuery.getAllNodes(snapshotId);
        var nodeFeatures = buildNodeFeatureMatrix(nodes);
        var nodeLabels = buildNodeLabelVector(nodes);

        // 2. Extract edges as adjacency list
        var edges = graphQuery.getAllEdges(snapshotId);
        var edgeIndex = buildEdgeIndex(edges);
        var edgeFeatures = buildEdgeFeatureMatrix(edges);

        return new GraphData(
            nodeFeatures,    // float[numNodes][featureDim]
            edgeIndex,       // long[2][numEdges]
            edgeFeatures,    // float[numEdges][edgeFeatureDim]
            nodeLabels,      // int[numNodes]
            nodes.size(),
            edges.size()
        );
    }

    /**
     * Build node feature matrix.
     * Features: [nodeType_onehot, degree, hasReactive, complexity_ordinal, ...]
     */
    private float[][] buildNodeFeatureMatrix(List<Map<String, Object>> nodes) {
        int featureDim = 32;  // Configurable
        var features = new float[nodes.size()][featureDim];

        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            features[i] = encodeNodeFeatures(node, featureDim);
        }
        return features;
    }

    private float[] encodeNodeFeatures(Map<String, Object> node, int dim) {
        var features = new float[dim];
        int idx = 0;

        // One-hot node type (SERVICE, CLASS, METHOD, ENDPOINT, etc.)
        var nodeType = (String) node.get("type");
        features[nodeTypeIndex(nodeType)] = 1.0f;
        idx += 10;  // 10 node types

        // Numeric features
        features[idx++] = ((Number) node.getOrDefault("degree", 0)).floatValue();
        features[idx++] = "true".equals(String.valueOf(node.getOrDefault("isReactive", false))) ? 1.0f : 0.0f;
        features[idx++] = complexityOrdinal((String) node.getOrDefault("reactiveComplexity", "NONE"));

        return features;
    }

    public record GraphData(
        float[][] nodeFeatures,
        long[][] edgeIndex,
        float[][] edgeFeatures,
        int[] nodeLabels,
        int numNodes,
        int numEdges
    ) {}
}
```

### 24.2 DJL model configuration

```java
@Configuration
public class GnnModelConfig {

    @Bean
    public Criteria<NDList, NDList> gnnLinkPredictionCriteria(FlowForgeProperties props) {
        return Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Path.of(props.gnn().linkPredictionModelPath()))
            .optEngine("OnnxRuntime")
            .optOption("interOpNumThreads", "4")
            .optOption("intraOpNumThreads", "4")
            .build();
    }

    @Bean
    public Criteria<NDList, NDList> gnnNodeClassificationCriteria(FlowForgeProperties props) {
        return Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Path.of(props.gnn().nodeClassificationModelPath()))
            .optEngine("OnnxRuntime")
            .build();
    }
}
```

### 24.3 GNN inference service

```java
@Service
public class GnnInferenceService implements AutoCloseable {

    private final ZooModel<NDList, NDList> linkPredModel;
    private final ZooModel<NDList, NDList> nodeClassModel;
    private final MeterRegistry meterRegistry;

    public GnnInferenceService(
            Criteria<NDList, NDList> gnnLinkPredictionCriteria,
            Criteria<NDList, NDList> gnnNodeClassificationCriteria,
            MeterRegistry meterRegistry) throws Exception {
        this.linkPredModel = gnnLinkPredictionCriteria.loadModel();
        this.nodeClassModel = gnnNodeClassificationCriteria.loadModel();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Predict missing links (edges) in the service graph.
     * Returns pairs of node indices with probability scores.
     */
    public List<LinkPrediction> predictLinks(GraphDataPreparer.GraphData graphData,
                                              double threshold) {
        return meterRegistry.timer("flowforge.gnn.link_prediction.latency").record(() -> {
            try (var predictor = linkPredModel.newPredictor();
                 var manager = NDManager.newBaseManager()) {

                var nodeFeatures = manager.create(graphData.nodeFeatures());
                var edgeIndex = manager.create(graphData.edgeIndex());

                var input = new NDList(nodeFeatures, edgeIndex);
                var output = predictor.predict(input);

                var scores = output.get(0).toFloatArray();
                int numNodes = graphData.numNodes();

                var predictions = new ArrayList<LinkPrediction>();
                // Only score node pairs where both indices are within bounds
                for (int i = 0; i < numNodes; i++) {
                    for (int j = i + 1; j < numNodes; j++) {
                        int idx = i * numNodes + j;
                        if (idx < scores.length && scores[idx] > threshold) {
                            predictions.add(new LinkPrediction(i, j, scores[idx]));
                        }
                    }
                }

                return predictions;
            } catch (Exception e) {
                log.error("GNN link prediction failed", e);
                return List.of();
            }
        });
    }

    /**
     * Classify nodes into interaction pattern categories.
     */
    public List<NodeClassification> classifyNodes(GraphDataPreparer.GraphData graphData) {
        return meterRegistry.timer("flowforge.gnn.node_classification.latency").record(() -> {
            try (var predictor = nodeClassModel.newPredictor();
                 var manager = NDManager.newBaseManager()) {

                var nodeFeatures = manager.create(graphData.nodeFeatures());
                var edgeIndex = manager.create(graphData.edgeIndex());

                var input = new NDList(nodeFeatures, edgeIndex);
                var output = predictor.predict(input);

                // Output: [numNodes, numClasses] — class probabilities
                var probabilities = output.get(0);
                var classifications = new ArrayList<NodeClassification>();

                for (int i = 0; i < graphData.numNodes(); i++) {
                    var nodeProbs = probabilities.get(i).toFloatArray();
                    int bestClass = argmax(nodeProbs);
                    classifications.add(new NodeClassification(
                        i, InteractionPattern.values()[bestClass], nodeProbs[bestClass]
                    ));
                }

                return classifications;
            } catch (Exception e) {
                log.error("GNN node classification failed", e);
                return List.of();
            }
        });
    }

    @Override
    public void close() {
        linkPredModel.close();
        nodeClassModel.close();
    }

    public record LinkPrediction(int sourceNodeIdx, int targetNodeIdx, float probability) {}
    public record NodeClassification(int nodeIdx, InteractionPattern pattern, float confidence) {}

    public enum InteractionPattern {
        GATEWAY, ORCHESTRATOR, WORKER, DATA_STORE_CONNECTOR,
        EVENT_PRODUCER, EVENT_CONSUMER, MIDDLEWARE
    }
}
```

### 24.4 GNN analysis service

```java
@Service
public class GnnAnalysisService {

    private final GraphDataPreparer dataPreparer;
    private final GnnInferenceService inference;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Run full GNN analysis on a snapshot's knowledge graph.
     */
    public GnnAnalysisResult analyze(UUID snapshotId) {
        // 1. Prepare graph data
        var graphData = dataPreparer.prepareGraphData(snapshotId);

        // 2. Link prediction — find missing connections
        var predictedLinks = inference.predictLinks(graphData, 0.7);

        // 3. Node classification — categorize services
        var nodeClasses = inference.classifyNodes(graphData);

        // 4. Store results
        var result = new GnnAnalysisResult(
            snapshotId,
            predictedLinks,
            nodeClasses,
            graphData.numNodes(),
            graphData.numEdges()
        );
        minio.putJson("evidence", "gnn-analysis/" + snapshotId + ".json", result);

        meterRegistry.counter("flowforge.gnn.links.predicted").increment(predictedLinks.size());

        return result;
    }
}

public record GnnAnalysisResult(
    UUID snapshotId,
    List<GnnInferenceService.LinkPrediction> predictedLinks,
    List<GnnInferenceService.NodeClassification> nodeClassifications,
    int totalNodes,
    int totalEdges
) {}
```

### 24.5 Model training script (Python — export to ONNX)

> GNN models are trained in Python using PyTorch Geometric and exported to ONNX for Java inference. This script runs offline, not as part of the Java application.

```python
# scripts/train_gnn.py
"""
Train GNN models (link prediction + node classification) and export to ONNX.

Usage:
    python scripts/train_gnn.py \
        --graph-data graph_export.json \
        --output-dir models/ \
        --epochs 100

Requires: pip install torch torch-geometric onnx numpy
"""
import torch
import torch.nn.functional as F
from torch_geometric.nn import GCNConv
from torch_geometric.data import Data
import numpy as np
import json, argparse

class LinkPredGNN(torch.nn.Module):
    def __init__(self, in_channels, hidden_channels):
        super().__init__()
        self.conv1 = GCNConv(in_channels, hidden_channels)
        self.conv2 = GCNConv(hidden_channels, hidden_channels)

    def encode(self, x, edge_index):
        x = self.conv1(x, edge_index).relu()
        return self.conv2(x, edge_index)

    def decode(self, z, edge_label_index):
        return (z[edge_label_index[0]] * z[edge_label_index[1]]).sum(dim=-1)

    def forward(self, x, edge_index):
        return self.encode(x, edge_index)

class NodeClassGNN(torch.nn.Module):
    def __init__(self, in_channels, hidden_channels, num_classes):
        super().__init__()
        self.conv1 = GCNConv(in_channels, hidden_channels)
        self.conv2 = GCNConv(hidden_channels, num_classes)

    def forward(self, x, edge_index):
        x = self.conv1(x, edge_index).relu()
        x = F.dropout(x, p=0.5, training=self.training)
        return self.conv2(x, edge_index)

def load_graph(path):
    with open(path) as f:
        data = json.load(f)
    x = torch.tensor(data["node_features"], dtype=torch.float)
    edge_index = torch.tensor(data["edge_index"], dtype=torch.long)
    y = torch.tensor(data.get("node_labels", [0]*len(data["node_features"])), dtype=torch.long)
    return Data(x=x, edge_index=edge_index, y=y)

def train_and_export(graph_path, output_dir, epochs):
    data = load_graph(graph_path)
    in_dim = data.x.shape[1]

    # Link prediction model
    link_model = LinkPredGNN(in_dim, 64)
    link_opt = torch.optim.Adam(link_model.parameters(), lr=0.01)
    for epoch in range(epochs):
        link_model.train()
        z = link_model.encode(data.x, data.edge_index)
        loss = -torch.log(torch.sigmoid(link_model.decode(z, data.edge_index)) + 1e-15).mean()
        loss.backward()
        link_opt.step()
        link_opt.zero_grad()
        if epoch % 20 == 0:
            print(f"Link pred epoch {epoch}: loss={loss.item():.4f}")

    link_model.eval()
    torch.onnx.export(link_model, (data.x, data.edge_index),
                      f"{output_dir}/gnn_link_pred.onnx",
                      input_names=["node_features", "edge_index"],
                      dynamic_axes={"node_features": {0: "num_nodes"},
                                    "edge_index": {1: "num_edges"}})
    print(f"Link prediction model exported to {output_dir}/gnn_link_pred.onnx")

    # Node classification model
    num_classes = int(data.y.max().item()) + 1 if data.y.max() > 0 else 7
    node_model = NodeClassGNN(in_dim, 64, num_classes)
    node_opt = torch.optim.Adam(node_model.parameters(), lr=0.01)
    for epoch in range(epochs):
        node_model.train()
        out = node_model(data.x, data.edge_index)
        loss = F.cross_entropy(out, data.y)
        loss.backward()
        node_opt.step()
        node_opt.zero_grad()
        if epoch % 20 == 0:
            print(f"Node class epoch {epoch}: loss={loss.item():.4f}")

    node_model.eval()
    torch.onnx.export(node_model, (data.x, data.edge_index),
                      f"{output_dir}/gnn_node_class.onnx",
                      input_names=["node_features", "edge_index"],
                      dynamic_axes={"node_features": {0: "num_nodes"},
                                    "edge_index": {1: "num_edges"}})
    print(f"Node classification model exported to {output_dir}/gnn_node_class.onnx")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--graph-data", required=True)
    parser.add_argument("--output-dir", default="models/")
    parser.add_argument("--epochs", type=int, default=100)
    args = parser.parse_args()
    train_and_export(args.graph_data, args.output_dir, args.epochs)
```

### 24.5b Graph data exporter for training

```java
/**
 * Export Neo4j graph data to JSON for Python GNN training.
 * Run this as a one-off command to generate training data.
 */
@Component
public class GraphDataExporter {

    private final Neo4jGraphQueryService graphQuery;
    private final ObjectMapper objectMapper;

    public void exportForTraining(UUID snapshotId, Path outputPath) throws Exception {
        var graphData = new GraphDataPreparer(graphQuery).prepareGraphData(snapshotId);
        var export = Map.of(
            "node_features", graphData.nodeFeatures(),
            "edge_index", graphData.edgeIndex(),
            "node_labels", graphData.nodeLabels(),
            "num_nodes", graphData.numNodes(),
            "num_edges", graphData.numEdges()
        );
        objectMapper.writeValue(outputPath.toFile(), export);
    }
}
```

> **Bootstrap workflow:** (1) Build knowledge graph (Stage 11) → (2) Export graph data
> with `GraphDataExporter` → (3) Train GNN models with `scripts/train_gnn.py` →
> (4) Place ONNX files under `models/` → (5) Configure paths in
> `flowforge.gnn.link-prediction-model-path` and `flowforge.gnn.node-classification-model-path`.
> Until trained models are available, the GNN stage is skipped via `run-gnn=false` in the
> Argo workflow (Stage 28) and the pipeline degrades gracefully using only observed graph edges.

### 24.6 Dependencies

```kotlin
// libs/gnn/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:graph"))
    implementation(libs.djl.api)                  // ai.djl:api:0.30.0
    implementation(libs.djl.onnxruntime)           // ai.djl.onnxruntime:onnxruntime-engine:0.30.0
}
```

Add to version catalog:
```toml
[versions]
djl = "0.30.0"

[libraries]
djl-api = { module = "ai.djl:api", version.ref = "djl" }
djl-onnxruntime = { module = "ai.djl.onnxruntime:onnxruntime-engine", version.ref = "djl" }
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |

**Argo task resource class:** GPU (`gpupool` node selector with `nvidia.com/gpu` tolerations) — DJL/ONNX Runtime inference requires GPU resources.

---

## Testing & Verification Strategy

### Unit Tests

**`GraphDataPreparerTest`** — validates node feature encoding, edge index construction, and tensor dimensions.

```java
@ExtendWith(MockitoExtension.class)
class GraphDataPreparerTest {

    @Mock Neo4jGraphQueryService graphQuery;

    @InjectMocks GraphDataPreparer preparer;

    @Test
    void prepareGraphData_returnsCorrectDimensions() {
        var nodes = TestFixtures.sampleGraphNodes(20);
        var edges = TestFixtures.sampleGraphEdges(30);
        when(graphQuery.getAllNodes(any())).thenReturn(nodes);
        when(graphQuery.getAllEdges(any())).thenReturn(edges);

        var data = preparer.prepareGraphData(UUID.randomUUID());

        assertThat(data.numNodes()).isEqualTo(20);
        assertThat(data.numEdges()).isEqualTo(30);
        assertThat(data.nodeFeatures()).hasNumberOfRows(20);
        assertThat(data.nodeFeatures()[0]).hasSize(32);
        assertThat(data.edgeIndex()).hasNumberOfRows(2);
        assertThat(data.edgeIndex()[0]).hasSize(30);
    }

    @Test
    void encodeNodeFeatures_setsOneHotForServiceType() {
        var serviceNode = Map.<String, Object>of(
            "type", "SERVICE", "degree", 5, "isReactive", "true",
            "reactiveComplexity", "COMPLEX");

        var features = preparer.encodeNodeFeatures(serviceNode, 32);

        assertThat(features[preparer.nodeTypeIndex("SERVICE")]).isEqualTo(1.0f);
        // All other type indices should be 0
        for (int i = 0; i < 10; i++) {
            if (i != preparer.nodeTypeIndex("SERVICE")) {
                assertThat(features[i]).isEqualTo(0.0f);
            }
        }
    }

    @Test
    void encodeNodeFeatures_encodesReactiveFlag() {
        var reactiveNode = Map.<String, Object>of(
            "type", "CLASS", "degree", 3, "isReactive", "true",
            "reactiveComplexity", "BRANCHING");

        var features = preparer.encodeNodeFeatures(reactiveNode, 32);

        assertThat(features[11]).isEqualTo(1.0f);  // isReactive at idx 11
    }

    @Test
    void encodeNodeFeatures_handlesDefaultValues() {
        var minimalNode = Map.<String, Object>of("type", "METHOD");

        var features = preparer.encodeNodeFeatures(minimalNode, 32);

        assertThat(features[10]).isEqualTo(0.0f);  // degree defaults to 0
        assertThat(features[11]).isEqualTo(0.0f);  // isReactive defaults to false
    }

    @Test
    void buildEdgeIndex_createsSourceTargetPairs() {
        var edges = List.of(
            Map.<String, Object>of("source", 0L, "target", 1L),
            Map.<String, Object>of("source", 1L, "target", 2L),
            Map.<String, Object>of("source", 0L, "target", 2L));

        var edgeIndex = preparer.buildEdgeIndex(edges);

        assertThat(edgeIndex[0]).containsExactly(0L, 1L, 0L);
        assertThat(edgeIndex[1]).containsExactly(1L, 2L, 2L);
    }

    @Test
    void prepareGraphData_emptyGraph_returnsEmptyData() {
        when(graphQuery.getAllNodes(any())).thenReturn(List.of());
        when(graphQuery.getAllEdges(any())).thenReturn(List.of());

        var data = preparer.prepareGraphData(UUID.randomUUID());

        assertThat(data.numNodes()).isZero();
        assertThat(data.numEdges()).isZero();
    }
}
```

**`GnnInferenceServiceTest`** — validates link prediction thresholding, node classification argmax, and resource management.

```java
@ExtendWith(MockitoExtension.class)
class GnnInferenceServiceTest {

    @Mock ZooModel<NDList, NDList> linkPredModel;
    @Mock ZooModel<NDList, NDList> nodeClassModel;
    @Mock MeterRegistry meterRegistry;

    GnnInferenceService service;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(new SimpleMeterRegistry().timer("test"));
        service = new GnnInferenceService(linkPredModel, nodeClassModel, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        service.close();
        verify(linkPredModel).close();
        verify(nodeClassModel).close();
    }

    @Test
    void predictLinks_filtersAboveThreshold() throws Exception {
        var predictor = mock(Predictor.class);
        when(linkPredModel.newPredictor()).thenReturn(predictor);
        // Simulate scores: some above 0.7, some below
        var mockOutput = TestFixtures.linkPredictionOutput(5, new float[]{
            0.9f, 0.3f, 0.8f, 0.1f, 0.75f, 0.6f, 0.95f, 0.2f, 0.4f, 0.85f
        });
        when(predictor.predict(any())).thenReturn(mockOutput);

        var graphData = TestFixtures.sampleGraphData(5, 8);
        var predictions = service.predictLinks(graphData, 0.7);

        assertThat(predictions).allMatch(p -> p.probability() > 0.7f);
    }

    @Test
    void predictLinks_returnsEmptyOnModelFailure() throws Exception {
        var predictor = mock(Predictor.class);
        when(linkPredModel.newPredictor()).thenReturn(predictor);
        when(predictor.predict(any())).thenThrow(new RuntimeException("ONNX error"));

        var graphData = TestFixtures.sampleGraphData(5, 8);
        var predictions = service.predictLinks(graphData, 0.7);

        assertThat(predictions).isEmpty();
    }

    @Test
    void classifyNodes_returnsClassificationForEachNode() throws Exception {
        var predictor = mock(Predictor.class);
        when(nodeClassModel.newPredictor()).thenReturn(predictor);
        var mockOutput = TestFixtures.nodeClassificationOutput(10, 7);
        when(predictor.predict(any())).thenReturn(mockOutput);

        var graphData = TestFixtures.sampleGraphData(10, 15);
        var classifications = service.classifyNodes(graphData);

        assertThat(classifications).hasSize(10);
        assertThat(classifications).allMatch(c ->
            c.pattern() != null && c.confidence() >= 0.0f);
    }

    @Test
    void classifyNodes_selectsHighestProbabilityClass() throws Exception {
        var predictor = mock(Predictor.class);
        when(nodeClassModel.newPredictor()).thenReturn(predictor);
        // Node 0: highest prob at index 2 (WORKER)
        var mockOutput = TestFixtures.nodeClassificationOutputFixed(
            new float[][]{{0.1f, 0.05f, 0.7f, 0.05f, 0.05f, 0.03f, 0.02f}});
        when(predictor.predict(any())).thenReturn(mockOutput);

        var graphData = TestFixtures.sampleGraphData(1, 0);
        var classifications = service.classifyNodes(graphData);

        assertThat(classifications.get(0).pattern())
            .isEqualTo(GnnInferenceService.InteractionPattern.WORKER);
    }
}
```

**`GnnAnalysisServiceTest`** — validates full analysis orchestration and MinIO result storage.

```java
@ExtendWith(MockitoExtension.class)
class GnnAnalysisServiceTest {

    @Mock GraphDataPreparer dataPreparer;
    @Mock GnnInferenceService inference;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks GnnAnalysisService analysisService;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString())).thenReturn(new SimpleMeterRegistry().counter("test"));
    }

    @Test
    void analyze_orchestratesPreparationAndInference() {
        var snapshotId = UUID.randomUUID();
        var graphData = TestFixtures.sampleGraphData(10, 15);
        when(dataPreparer.prepareGraphData(snapshotId)).thenReturn(graphData);
        when(inference.predictLinks(graphData, 0.7))
            .thenReturn(List.of(new GnnInferenceService.LinkPrediction(0, 1, 0.85f)));
        when(inference.classifyNodes(graphData))
            .thenReturn(List.of(new GnnInferenceService.NodeClassification(
                0, GnnInferenceService.InteractionPattern.GATEWAY, 0.9f)));

        var result = analysisService.analyze(snapshotId);

        assertThat(result.predictedLinks()).hasSize(1);
        assertThat(result.nodeClassifications()).hasSize(1);
        assertThat(result.totalNodes()).isEqualTo(10);
        verify(minio).putJson(eq("evidence"),
            eq("gnn-analysis/" + snapshotId + ".json"), eq(result));
    }
}
```

### Integration Tests

**`GnnInferenceIntegrationTest`** — end-to-end ONNX model loading and inference with a small test graph.

```java
@SpringBootTest
@Tag("integration")
@Tag("requires-onnx-models")
class GnnInferenceIntegrationTest {

    @Autowired GnnInferenceService inferenceService;

    @Test
    void linkPrediction_withSmallTestGraph_returnsResults() {
        var graphData = TestFixtures.smallTestGraphData();

        var predictions = inferenceService.predictLinks(graphData, 0.5);

        assertThat(predictions).isNotNull();
        // With a valid ONNX model, predictions should be non-empty for a connected graph
    }

    @Test
    void nodeClassification_withSmallTestGraph_classifiesAllNodes() {
        var graphData = TestFixtures.smallTestGraphData();

        var classifications = inferenceService.classifyNodes(graphData);

        assertThat(classifications).hasSize(graphData.numNodes());
    }
}
```

**`GnnAnalysisIntegrationTest`** — full pipeline with Neo4j Testcontainer and MinIO.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class GnnAnalysisIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.18-community")
        .withoutAuthentication();

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-02-17T01-15-57Z");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("flowforge.minio.endpoint", minio::getS3URL);
        registry.add("flowforge.gnn.link-prediction-model-path",
            () -> "src/test/resources/models/test_link_pred.onnx");
        registry.add("flowforge.gnn.node-classification-model-path",
            () -> "src/test/resources/models/test_node_class.onnx");
    }

    @Autowired GnnAnalysisService analysisService;
    @Autowired Neo4jGraphQueryService graphQuery;

    @BeforeEach
    void seedGraph() {
        TestFixtures.seedNeo4jTestGraph(graphQuery);
    }

    @Test
    void analyze_fullPipelineFromNeo4jToMinio() {
        var snapshotId = TestFixtures.TEST_SNAPSHOT_ID;

        var result = analysisService.analyze(snapshotId);

        assertThat(result.totalNodes()).isGreaterThan(0);
        assertThat(result.totalEdges()).isGreaterThan(0);
    }
}
```

### Test Fixtures & Sample Data

Create test fixtures at `libs/gnn/src/test/java/com/flowforge/gnn/TestFixtures.java`:

- **`sampleGraphNodes(int count)`** — list of node maps with randomized types (SERVICE, CLASS, METHOD), degrees, and reactive flags
- **`sampleGraphEdges(int count)`** — list of edge maps with valid source/target indices
- **`sampleGraphData(int nodes, int edges)`** — pre-built `GraphData` record with correct float[][] and long[][] dimensions
- **`smallTestGraphData()`** — a minimal 5-node, 7-edge graph representing Gateway → 2 services → DB + Cache for smoke tests
- **`linkPredictionOutput(int nodes, float[] scores)`** — mock NDList output simulating link prediction scores
- **`nodeClassificationOutput(int nodes, int numClasses)`** — mock NDList output with random class probabilities
- **`nodeClassificationOutputFixed(float[][] probs)`** — deterministic class probabilities for argmax verification

Create small test ONNX models under `libs/gnn/src/test/resources/models/`:

- **`test_link_pred.onnx`** — a tiny 32-dim input ONNX model generated by `scripts/generate_test_models.py` for integration testing (not a trained model, just valid ONNX structure)
- **`test_node_class.onnx`** — a tiny 32-dim → 7-class ONNX model for node classification integration tests

Create a helper script `scripts/generate_test_models.py` that generates these minimal ONNX models for CI:

```python
# scripts/generate_test_models.py
import torch
import torch.nn as nn

class TinyLinkPred(nn.Module):
    def __init__(self):
        super().__init__()
        self.linear = nn.Linear(32, 16)
    def forward(self, x, edge_index):
        return self.linear(x)

class TinyNodeClass(nn.Module):
    def __init__(self):
        super().__init__()
        self.linear = nn.Linear(32, 7)
    def forward(self, x, edge_index):
        return self.linear(x)

# Export both
dummy_x = torch.randn(5, 32)
dummy_ei = torch.tensor([[0,1,2], [1,2,3]], dtype=torch.long)

torch.onnx.export(TinyLinkPred(), (dummy_x, dummy_ei),
    "libs/gnn/src/test/resources/models/test_link_pred.onnx",
    input_names=["node_features", "edge_index"],
    dynamic_axes={"node_features": {0: "num_nodes"}, "edge_index": {1: "num_edges"}})

torch.onnx.export(TinyNodeClass(), (dummy_x, dummy_ei),
    "libs/gnn/src/test/resources/models/test_node_class.onnx",
    input_names=["node_features", "edge_index"],
    dynamic_axes={"node_features": {0: "num_nodes"}, "edge_index": {1: "num_edges"}})
```

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `Neo4jGraphQueryService` | **Mock** (unit) / **Testcontainers Neo4j** (integration) | Unit tests use canned node/edge lists; integration tests query real Neo4j |
| `ZooModel` (DJL) | **Mock** (unit) | ONNX model loading is slow and requires model files; mock the predictor interface |
| `NDManager` | **Real** (unit) | NDManager is lightweight and used for tensor creation — safe to use directly |
| `Predictor` | **Mock** (unit) | Mock `predict()` to return controlled NDList outputs |
| `MinioStorageClient` | **Mock** (unit) / **Testcontainers** (integration) | Unit tests verify calls; integration tests verify persistence |
| `MeterRegistry` | **SimpleMeterRegistry** | In-memory registry for timer/counter verification |

### CI/CD Considerations

- Tag unit tests with `@Tag("unit")`, integration tests with `@Tag("integration")`
- ONNX model integration tests additionally tagged `@Tag("requires-onnx-models")` — skip if test models are unavailable
- Run `scripts/generate_test_models.py` as a CI setup step to produce test ONNX models before integration tests
- Neo4j Testcontainer requires Docker and ~2GB RAM — allocate sufficient resources on CI runners
- DJL ONNX Runtime engine downloads native libraries on first use — pre-cache in Docker image or set `DJL_CACHE_DIR` in CI
- Python training script (`train_gnn.py`) validation: add a CI job that runs the script with a tiny synthetic graph to verify it completes without errors
- Set `TESTCONTAINERS_REUSE_ENABLE=true` for faster local development with persistent Neo4j and MinIO containers

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Graph data prep | Neo4j with 20 nodes, 30 edges | GraphData with correct dimensions |
| Node features | Check feature matrix | 32-dim float vectors |
| Edge index | Check adjacency | Correct source-target pairs |
| ONNX model load | Load link_pred.onnx | Model loaded without error |
| Link prediction | Run on test graph | Predicted links with probabilities |
| Threshold filter | threshold=0.7 | Only high-confidence links |
| Node classification | Run on test graph | Each node categorized |
| Pattern types | Check classifications | Valid InteractionPattern enum values |
| Empty graph | No nodes/edges | Empty results, no crash |
| MinIO storage | Run analysis | GNN results in evidence bucket |
| Resource cleanup | Close service | Models released |
| Metrics | Run analysis | flowforge.gnn.link_prediction.latency populated |

## Files to create

- `libs/gnn/build.gradle.kts`
- `libs/gnn/src/main/java/com/flowforge/gnn/data/GraphDataPreparer.java`
- `libs/gnn/src/main/java/com/flowforge/gnn/config/GnnModelConfig.java`
- `libs/gnn/src/main/java/com/flowforge/gnn/inference/GnnInferenceService.java`
- `libs/gnn/src/main/java/com/flowforge/gnn/service/GnnAnalysisService.java`
- `libs/gnn/src/test/java/.../GraphDataPreparerTest.java`
- `libs/gnn/src/test/java/.../GnnInferenceServiceTest.java`
- `scripts/train_gnn.py` (Python training script — reference only)

## Depends on

- Stage 11 (Neo4j knowledge graph)

## Produces

- GNN link predictions (predicted missing service connections)
- GNN node classifications (service interaction patterns)
- ONNX model inference via DJL — no Python runtime required
