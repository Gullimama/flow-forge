# Stage 25 — Migration Pattern Classifier (DJL)

## Goal

Build a **migration pattern classifier** using DJL that categorizes code artifacts and service interactions into migration difficulty classes. The classifier uses pre-trained embeddings to predict how complex each component will be to migrate from Java 11/Micronaut to a target framework. Outputs feed into the risk assessment and migration planning stages.

## Prerequisites

- Stage 15 (code embeddings)
- Stage 24 (GNN interaction patterns)

## What to build

### 25.1 Classification taxonomy

```java
public sealed interface MigrationClassification {

    record ReactiveComplexityClass(
        String classFqn,
        MigrationDifficulty difficulty,
        double confidence,
        List<String> reasons
    ) implements MigrationClassification {}

    record FrameworkCouplingClass(
        String classFqn,
        CouplingLevel coupling,
        double confidence,
        List<String> frameworkApis
    ) implements MigrationClassification {}

    record DataAccessPatternClass(
        String classFqn,
        DataAccessPattern pattern,
        double confidence,
        String migrationNote
    ) implements MigrationClassification {}

    enum MigrationDifficulty { TRIVIAL, LOW, MEDIUM, HIGH, VERY_HIGH }
    enum CouplingLevel { NONE, LIGHT, MODERATE, TIGHT, LOCKED_IN }
    enum DataAccessPattern { JPA, RAW_SQL, REACTIVE_MONGO, REACTIVE_R2DBC, MIXED, NONE }
}
```

### 25.2 Feature extractor for classification

```java
@Component
public class MigrationFeatureExtractor {

    private final EmbeddingModel codeEmbeddingModel;

    /**
     * Extract classification features from a code chunk.
     * Combines code embedding + handcrafted features.
     */
    public float[] extractFeatures(ParsedClass parsedClass) {
        // 1. Code embedding (1024-dim from CodeSage)
        var codeText = parsedClass.rawSource();
        var embedding = codeEmbeddingModel.embed(codeText);

        // 2. Handcrafted features (64-dim)
        var handcrafted = new float[64];
        int idx = 0;

        // Reactive operator count
        handcrafted[idx++] = countReactiveOperators(parsedClass);

        // Framework annotation count
        handcrafted[idx++] = countFrameworkAnnotations(parsedClass);

        // DI complexity (number of injected fields)
        handcrafted[idx++] = parsedClass.fields().stream()
            .filter(ParsedField::isInjected).count();

        // Method count
        handcrafted[idx++] = parsedClass.methods().size();

        // Has reactive return types (Mono, Flux, Publisher, etc.)
        handcrafted[idx++] = parsedClass.methods().stream()
            .anyMatch(m -> isReactiveReturnType(m.returnType())) ? 1.0f : 0.0f;

        // HTTP endpoint count
        handcrafted[idx++] = parsedClass.methods().stream()
            .filter(m -> !m.httpMethods().isEmpty()).count();

        // Has Kafka annotations
        handcrafted[idx++] = parsedClass.methods().stream()
            .anyMatch(m -> m.annotations().stream()
                .anyMatch(a -> a.contains("Kafka"))) ? 1.0f : 0.0f;

        // Interface implementation count
        handcrafted[idx++] = parsedClass.implementedInterfaces().size();

        // Lines of code
        handcrafted[idx++] = parsedClass.lineEnd() - parsedClass.lineStart();

        // Max reactive complexity among methods
        handcrafted[idx++] = parsedClass.methods().stream()
            .mapToInt(m -> m.reactiveComplexity().ordinal())
            .max().orElse(0);

        // 3. Concatenate: embedding + handcrafted
        var combined = new float[embedding.length + handcrafted.length];
        System.arraycopy(embedding, 0, combined, 0, embedding.length);
        System.arraycopy(handcrafted, 0, combined, embedding.length, handcrafted.length);

        return combined;
    }
}
```

### 25.3 DJL classifier

```java
@Service
public class MigrationClassifier implements AutoCloseable {

    private final ZooModel<NDList, NDList> classifierModel;
    private final MigrationFeatureExtractor featureExtractor;
    private final MeterRegistry meterRegistry;

    public MigrationClassifier(FlowForgeProperties props,
                                MigrationFeatureExtractor featureExtractor,
                                MeterRegistry meterRegistry) throws Exception {
        var criteria = Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Path.of(props.gnn().classifierModelPath()))
            .optEngine("OnnxRuntime")
            .build();

        this.classifierModel = criteria.loadModel();
        this.featureExtractor = featureExtractor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Classify a parsed class for migration difficulty.
     */
    public MigrationClassification.ReactiveComplexityClass classifyReactiveComplexity(
            ParsedClass parsedClass) {
        return meterRegistry.timer("flowforge.classifier.reactive.latency").record(() -> {
            try (var predictor = classifierModel.newPredictor()) {
                var manager = NDManager.newBaseManager();
                var features = featureExtractor.extractFeatures(parsedClass);
                var input = new NDList(manager.create(features));

                var output = predictor.predict(input);
                var probs = output.get(0).toFloatArray();
                int bestClass = argmax(probs);

                manager.close();
                return new MigrationClassification.ReactiveComplexityClass(
                    parsedClass.fqn(),
                    MigrationClassification.MigrationDifficulty.values()[bestClass],
                    probs[bestClass],
                    generateReasons(parsedClass, bestClass)
                );
            } catch (Exception e) {
                log.error("Classification failed for {}", parsedClass.fqn(), e);
                return fallbackClassification(parsedClass);
            }
        });
    }

    /**
     * Rule-based fallback when model is unavailable.
     */
    private MigrationClassification.ReactiveComplexityClass fallbackClassification(
            ParsedClass parsedClass) {
        var maxComplexity = parsedClass.methods().stream()
            .map(ParsedMethod::reactiveComplexity)
            .max(Comparator.naturalOrder())
            .orElse(ReactiveComplexity.NONE);

        var difficulty = switch (maxComplexity) {
            case NONE -> MigrationClassification.MigrationDifficulty.TRIVIAL;
            case LINEAR -> MigrationClassification.MigrationDifficulty.LOW;
            case BRANCHING -> MigrationClassification.MigrationDifficulty.MEDIUM;
            case COMPLEX -> MigrationClassification.MigrationDifficulty.HIGH;
        };

        return new MigrationClassification.ReactiveComplexityClass(
            parsedClass.fqn(), difficulty, 0.5,
            List.of("Fallback: rule-based classification from reactive complexity")
        );
    }

    @Override
    public void close() {
        classifierModel.close();
    }
}
```

### 25.4 Classification service

```java
@Service
public class MigrationClassificationService {

    private final MigrationClassifier classifier;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Classify all code artifacts in a snapshot.
     */
    public ClassificationResult classifySnapshot(UUID snapshotId, List<ParsedClass> classes) {
        var classifications = classes.stream()
            .map(classifier::classifyReactiveComplexity)
            .toList();

        var byDifficulty = classifications.stream()
            .collect(Collectors.groupingBy(
                MigrationClassification.ReactiveComplexityClass::difficulty,
                Collectors.counting()));

        // Store results
        minio.putJson("evidence", "classification/" + snapshotId + ".json",
            Map.of("classifications", classifications, "summary", byDifficulty));

        meterRegistry.counter("flowforge.classification.total").increment(classifications.size());
        byDifficulty.forEach((diff, count) ->
            meterRegistry.counter("flowforge.classification.difficulty", "level", diff.name())
                .increment(count));

        return new ClassificationResult(classifications, byDifficulty);
    }
}

public record ClassificationResult(
    List<MigrationClassification.ReactiveComplexityClass> classifications,
    Map<MigrationClassification.MigrationDifficulty, Long> byDifficulty
) {}
```

### 25.5 Dependencies

```kotlin
// libs/classifier/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:code-parser"))
    implementation(project(":libs:embedding"))
    implementation(libs.djl.api)
    implementation(libs.djl.onnxruntime)
}
```

### 25.6 Model training pipeline

The ONNX classifier model used in 25.3 must be trained offline before deployment. FlowForge provides a training pipeline that uses labelled code artifacts to produce the `classifier.onnx` model.

#### Training data preparation

```java
/**
 * Generates training data from manually-labelled code artifacts.
 * Each row is a 1088-dim feature vector + difficulty label.
 *
 * Run this as a one-off command: ./gradlew :libs:classifier:run --args="generate-training-data"
 */
@Component
public class TrainingDataGenerator {

    private final MigrationFeatureExtractor featureExtractor;
    private final ObjectMapper objectMapper;

    /**
     * Read labelled examples from a JSONL file and produce feature vectors.
     *
     * Input format (one per line):
     *   {"source_path": "com/example/BookingController.java", "difficulty": "HIGH", "source": "..."}
     *
     * Output: training-data.npz (NumPy-compatible) with 'features' and 'labels' arrays.
     */
    public void generate(Path labelledDataPath, Path outputPath) throws Exception {
        var lines = Files.readAllLines(labelledDataPath);
        var features = new float[lines.size()][];
        var labels = new int[lines.size()];

        for (int i = 0; i < lines.size(); i++) {
            var entry = objectMapper.readTree(lines.get(i));
            var parsed = parseSource(entry.get("source").asText());
            features[i] = featureExtractor.extractFeatures(parsed);
            labels[i] = MigrationClassification.MigrationDifficulty
                .valueOf(entry.get("difficulty").asText()).ordinal();
        }

        // Export as DJL NDArray for training
        try (var manager = NDManager.newBaseManager()) {
            var featNd = manager.create(features);
            var labelNd = manager.create(labels);
            NDSerializer.save(List.of(featNd, labelNd), outputPath);
        }

        log.info("Generated training data: {} samples, {} features",
            features.length, features[0].length);
    }
}
```

#### Model training (Python script)

The classifier is trained using a lightweight Python script (scikit-learn or PyTorch) and exported to ONNX:

```python
# tools/train_classifier.py
"""
Train migration difficulty classifier and export to ONNX.

Usage:
    python tools/train_classifier.py \
        --data training-data.npz \
        --output models/classifier.onnx \
        --epochs 50

Requires: pip install scikit-learn skl2onnx numpy torch onnx
"""
import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import cross_val_score
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
import argparse, json

def train(data_path: str, output_path: str):
    data = np.load(data_path)
    X, y = data["features"], data["labels"]

    # Gradient Boosted Trees — works well with mixed embedding + handcrafted features
    clf = GradientBoostingClassifier(
        n_estimators=200, max_depth=6, learning_rate=0.1,
        subsample=0.8, min_samples_leaf=5
    )

    # Cross-validation
    scores = cross_val_score(clf, X, y, cv=5, scoring="accuracy")
    print(f"CV accuracy: {scores.mean():.3f} ± {scores.std():.3f}")

    clf.fit(X, y)

    # Export to ONNX
    initial_type = [("input", FloatTensorType([None, X.shape[1]]))]
    onnx_model = convert_sklearn(clf, initial_types=initial_type,
                                  target_opset=17)

    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())

    # Save label mapping
    labels = ["TRIVIAL", "LOW", "MEDIUM", "HIGH", "VERY_HIGH"]
    with open(output_path.replace(".onnx", "_labels.json"), "w") as f:
        json.dump({"labels": labels, "cv_accuracy": float(scores.mean())}, f)

    print(f"Model saved to {output_path}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True)
    parser.add_argument("--output", default="models/classifier.onnx")
    args = parser.parse_args()
    train(args.data, args.output)
```

#### Bootstrapping without labelled data

When no manually-labelled training data is available, use the **rule-based fallback** (25.3) as a bootstrapping teacher:

1. Run the rule-based classifier on all code artifacts in the codebase
2. Manually review and correct ~200 samples (focus on edge cases)
3. Use corrected samples as training data for the ONNX model
4. Compare model predictions against rule-based predictions; log disagreements for review
5. Re-train iteratively as more labelled data becomes available

Track all training runs via MLflow (Stage 26): accuracy, F1 per class, confusion matrix, and feature importance.

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| Qdrant | `flowforge-qdrant.flowforge-infra.svc.cluster.local` | 6334 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |

**Argo task resource class:** GPU (`gpupool` node selector with `nvidia.com/gpu` tolerations) — DJL/ONNX Runtime inference requires GPU resources.

---

## Testing & Verification Strategy

### Unit Tests

**`MigrationFeatureExtractorTest`** — validates feature vector dimensions, reactive operator counting, and embedding concatenation.

```java
@ExtendWith(MockitoExtension.class)
class MigrationFeatureExtractorTest {

    @Mock EmbeddingModel codeEmbeddingModel;

    @InjectMocks MigrationFeatureExtractor extractor;

    @Test
    void extractFeatures_returns1088DimVector() {
        var parsedClass = TestFixtures.micronautController();
        when(codeEmbeddingModel.embed(anyString()))
            .thenReturn(new float[1024]);

        var features = extractor.extractFeatures(parsedClass);

        assertThat(features).hasSize(1088);  // 1024 embedding + 64 handcrafted
    }

    @Test
    void extractFeatures_countsReactiveOperators() {
        var reactiveClass = TestFixtures.reactiveServiceWith(
            "flatMap", "switchMap", "concatMap", "zipWith", "retryWhen");
        when(codeEmbeddingModel.embed(anyString()))
            .thenReturn(new float[1024]);

        var features = extractor.extractFeatures(reactiveClass);

        assertThat(features[1024]).isEqualTo(5.0f);  // idx 0 in handcrafted
    }

    @Test
    void extractFeatures_countsFrameworkAnnotations() {
        var annotatedClass = TestFixtures.classWithAnnotations(
            "@Controller", "@Get", "@Inject", "@Singleton");
        when(codeEmbeddingModel.embed(anyString()))
            .thenReturn(new float[1024]);

        var features = extractor.extractFeatures(annotatedClass);

        assertThat(features[1025]).isEqualTo(4.0f);  // idx 1 in handcrafted
    }

    @Test
    void extractFeatures_detectsReactiveReturnTypes() {
        var monoClass = TestFixtures.classWithReactiveReturnTypes();
        when(codeEmbeddingModel.embed(anyString()))
            .thenReturn(new float[1024]);

        var features = extractor.extractFeatures(monoClass);

        assertThat(features[1028]).isEqualTo(1.0f);  // hasReactiveReturnTypes flag
    }

    @Test
    void extractFeatures_detectsKafkaAnnotations() {
        var kafkaClass = TestFixtures.kafkaListenerClass();
        when(codeEmbeddingModel.embed(anyString()))
            .thenReturn(new float[1024]);

        var features = extractor.extractFeatures(kafkaClass);

        assertThat(features[1030]).isEqualTo(1.0f);  // hasKafka flag
    }

    @Test
    void extractFeatures_computesMaxReactiveComplexity() {
        var mixedClass = TestFixtures.classWithMixedMethods(
            ReactiveComplexity.NONE, ReactiveComplexity.BRANCHING, ReactiveComplexity.LINEAR);
        when(codeEmbeddingModel.embed(anyString()))
            .thenReturn(new float[1024]);

        var features = extractor.extractFeatures(mixedClass);

        // BRANCHING.ordinal() = 2, which is the max
        assertThat(features[1033]).isEqualTo(2.0f);
    }

    @Test
    void extractFeatures_concatenatesEmbeddingAndHandcrafted() {
        var parsedClass = TestFixtures.simplePojo();
        var embedding = new float[1024];
        Arrays.fill(embedding, 0.5f);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(embedding);

        var features = extractor.extractFeatures(parsedClass);

        // First 1024 should be embedding values
        for (int i = 0; i < 1024; i++) {
            assertThat(features[i]).isEqualTo(0.5f);
        }
    }
}
```

**`MigrationClassifierTest`** — validates ONNX inference, argmax selection, confidence scores, and rule-based fallback.

```java
@ExtendWith(MockitoExtension.class)
class MigrationClassifierTest {

    @Mock ZooModel<NDList, NDList> classifierModel;
    @Mock MigrationFeatureExtractor featureExtractor;
    @Mock MeterRegistry meterRegistry;

    MigrationClassifier classifier;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(new SimpleMeterRegistry().timer("test"));
        classifier = new MigrationClassifier(classifierModel, featureExtractor, meterRegistry);
    }

    @Test
    void classifyReactiveComplexity_returnsCorrectDifficulty() throws Exception {
        var parsedClass = TestFixtures.micronautController();
        when(featureExtractor.extractFeatures(parsedClass))
            .thenReturn(new float[1088]);
        var predictor = mock(Predictor.class);
        when(classifierModel.newPredictor()).thenReturn(predictor);
        // Probabilities: TRIVIAL=0.05, LOW=0.1, MEDIUM=0.15, HIGH=0.6, VERY_HIGH=0.1
        var output = TestFixtures.classifierOutput(
            new float[]{0.05f, 0.1f, 0.15f, 0.6f, 0.1f});
        when(predictor.predict(any())).thenReturn(output);

        var result = classifier.classifyReactiveComplexity(parsedClass);

        assertThat(result.difficulty())
            .isEqualTo(MigrationClassification.MigrationDifficulty.HIGH);
        assertThat(result.confidence()).isCloseTo(0.6, within(0.01));
        assertThat(result.classFqn()).isEqualTo(parsedClass.fqn());
    }

    @Test
    void classifyReactiveComplexity_fallsBackToRulesOnModelFailure() throws Exception {
        var parsedClass = TestFixtures.reactiveServiceWith("flatMap");
        when(featureExtractor.extractFeatures(parsedClass))
            .thenReturn(new float[1088]);
        var predictor = mock(Predictor.class);
        when(classifierModel.newPredictor()).thenReturn(predictor);
        when(predictor.predict(any()))
            .thenThrow(new RuntimeException("ONNX inference error"));

        var result = classifier.classifyReactiveComplexity(parsedClass);

        assertThat(result.confidence()).isEqualTo(0.5);
        assertThat(result.reasons()).contains(
            "Fallback: rule-based classification from reactive complexity");
    }

    @Test
    void fallbackClassification_mapsReactiveComplexityToDifficulty() {
        var noneClass = TestFixtures.classWithMaxReactiveComplexity(ReactiveComplexity.NONE);
        var linearClass = TestFixtures.classWithMaxReactiveComplexity(ReactiveComplexity.LINEAR);
        var branchingClass = TestFixtures.classWithMaxReactiveComplexity(ReactiveComplexity.BRANCHING);
        var complexClass = TestFixtures.classWithMaxReactiveComplexity(ReactiveComplexity.COMPLEX);

        assertThat(classifier.fallbackClassification(noneClass).difficulty())
            .isEqualTo(MigrationClassification.MigrationDifficulty.TRIVIAL);
        assertThat(classifier.fallbackClassification(linearClass).difficulty())
            .isEqualTo(MigrationClassification.MigrationDifficulty.LOW);
        assertThat(classifier.fallbackClassification(branchingClass).difficulty())
            .isEqualTo(MigrationClassification.MigrationDifficulty.MEDIUM);
        assertThat(classifier.fallbackClassification(complexClass).difficulty())
            .isEqualTo(MigrationClassification.MigrationDifficulty.HIGH);
    }

    @Test
    void close_releasesModel() {
        classifier.close();

        verify(classifierModel).close();
    }
}
```

**`MigrationClassificationServiceTest`** — validates batch classification, grouping by difficulty, and MinIO storage.

```java
@ExtendWith(MockitoExtension.class)
class MigrationClassificationServiceTest {

    @Mock MigrationClassifier classifier;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks MigrationClassificationService service;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString()))
            .thenReturn(new SimpleMeterRegistry().counter("test"));
        when(meterRegistry.counter(anyString(), anyString(), anyString()))
            .thenReturn(new SimpleMeterRegistry().counter("test"));
    }

    @Test
    void classifySnapshot_classifiesAllClasses() {
        var classes = List.of(
            TestFixtures.simplePojo(),
            TestFixtures.micronautController(),
            TestFixtures.kafkaListenerClass());
        when(classifier.classifyReactiveComplexity(any()))
            .thenReturn(TestFixtures.classificationResult(
                MigrationClassification.MigrationDifficulty.LOW));

        var result = service.classifySnapshot(UUID.randomUUID(), classes);

        assertThat(result.classifications()).hasSize(3);
        verify(classifier, times(3)).classifyReactiveComplexity(any());
    }

    @Test
    void classifySnapshot_groupsByDifficulty() {
        var classes = List.of(
            TestFixtures.simplePojo(),
            TestFixtures.micronautController());
        when(classifier.classifyReactiveComplexity(classes.get(0)))
            .thenReturn(TestFixtures.classificationResult(
                MigrationClassification.MigrationDifficulty.TRIVIAL));
        when(classifier.classifyReactiveComplexity(classes.get(1)))
            .thenReturn(TestFixtures.classificationResult(
                MigrationClassification.MigrationDifficulty.HIGH));

        var result = service.classifySnapshot(UUID.randomUUID(), classes);

        assertThat(result.byDifficulty())
            .containsEntry(MigrationClassification.MigrationDifficulty.TRIVIAL, 1L)
            .containsEntry(MigrationClassification.MigrationDifficulty.HIGH, 1L);
    }

    @Test
    void classifySnapshot_storesResultsInMinio() {
        var snapshotId = UUID.randomUUID();
        var classes = List.of(TestFixtures.simplePojo());
        when(classifier.classifyReactiveComplexity(any()))
            .thenReturn(TestFixtures.classificationResult(
                MigrationClassification.MigrationDifficulty.LOW));

        service.classifySnapshot(snapshotId, classes);

        verify(minio).putJson(eq("evidence"),
            eq("classification/" + snapshotId + ".json"), anyMap());
    }
}
```

**`TrainingDataGeneratorTest`** — validates labelled data parsing and feature vector generation.

```java
@ExtendWith(MockitoExtension.class)
class TrainingDataGeneratorTest {

    @Mock MigrationFeatureExtractor featureExtractor;
    @Mock ObjectMapper objectMapper;

    @InjectMocks TrainingDataGenerator generator;

    @Test
    void generate_producesCorrectNumberOfSamples(@TempDir Path tempDir) throws Exception {
        var labelledData = tempDir.resolve("labelled.jsonl");
        Files.writeString(labelledData,
            "{\"source_path\":\"Foo.java\",\"difficulty\":\"HIGH\",\"source\":\"class Foo {}\"}\n" +
            "{\"source_path\":\"Bar.java\",\"difficulty\":\"LOW\",\"source\":\"class Bar {}\"}");

        when(featureExtractor.extractFeatures(any()))
            .thenReturn(new float[1088]);

        var outputPath = tempDir.resolve("training-data.ndarray");
        generator.generate(labelledData, outputPath);

        assertThat(outputPath).exists();
    }
}
```

### Integration Tests

**`MigrationClassifierIntegrationTest`** — end-to-end classification with real ONNX model and Testcontainers MinIO.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
@Tag("requires-onnx-models")
class MigrationClassifierIntegrationTest {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-02-17T01-15-57Z");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("flowforge.minio.endpoint", minio::getS3URL);
        registry.add("flowforge.gnn.classifier-model-path",
            () -> "src/test/resources/models/test_classifier.onnx");
    }

    @Autowired MigrationClassificationService service;

    @Test
    void classifySnapshot_endToEnd() {
        var classes = List.of(
            TestFixtures.simplePojo(),
            TestFixtures.micronautController());

        var result = service.classifySnapshot(UUID.randomUUID(), classes);

        assertThat(result.classifications()).hasSize(2);
        assertThat(result.classifications()).allMatch(c ->
            c.difficulty() != null && c.confidence() > 0.0);
    }
}
```

**`MigrationClassifierFallbackIntegrationTest`** — validates graceful degradation when the ONNX model is unavailable.

```java
@SpringBootTest
@Tag("integration")
@TestPropertySource(properties = {
    "flowforge.gnn.classifier-model-path=nonexistent/model.onnx"
})
class MigrationClassifierFallbackIntegrationTest {

    @Autowired MigrationClassificationService service;

    @Test
    void classifySnapshot_fallsBackToRuleBased_whenModelUnavailable() {
        var classes = List.of(TestFixtures.reactiveServiceWith("flatMap", "switchMap"));

        var result = service.classifySnapshot(UUID.randomUUID(), classes);

        assertThat(result.classifications()).hasSize(1);
        assertThat(result.classifications().get(0).reasons())
            .anyMatch(r -> r.contains("Fallback"));
    }
}
```

### Test Fixtures & Sample Data

Create test fixtures at `libs/classifier/src/test/java/com/flowforge/classifier/TestFixtures.java`:

- **`simplePojo()`** — a `ParsedClass` with no reactive code, no annotations, 3 getter methods — expected: TRIVIAL
- **`micronautController()`** — a `ParsedClass` with `@Controller`, `@Get`, `@Inject`, 5 endpoint methods, no reactive types — expected: LOW/MEDIUM
- **`kafkaListenerClass()`** — a `ParsedClass` with `@KafkaListener` annotation, 2 `@Topic` methods — expected: MEDIUM
- **`reactiveServiceWith(String... operators)`** — a `ParsedClass` with configurable reactive operator occurrences (flatMap, switchMap, etc.)
- **`classWithAnnotations(String... annotations)`** — a `ParsedClass` with specified annotations for feature counting tests
- **`classWithReactiveReturnTypes()`** — a `ParsedClass` where methods return `Mono<T>`, `Flux<T>`, or `Publisher<T>`
- **`classWithMixedMethods(ReactiveComplexity... complexities)`** — a `ParsedClass` with methods at varying reactive complexity levels
- **`classWithMaxReactiveComplexity(ReactiveComplexity level)`** — single-method class at the specified complexity level for fallback tests
- **`classificationResult(MigrationDifficulty difficulty)`** — pre-built `ReactiveComplexityClass` for service-level mocking
- **`classifierOutput(float[] probs)`** — mock NDList output from the classifier ONNX model

Generate a test ONNX model at `libs/classifier/src/test/resources/models/test_classifier.onnx` using:

```python
# scripts/generate_test_classifier.py
import torch
import torch.nn as nn

class TinyClassifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.linear = nn.Linear(1088, 5)  # 5 difficulty classes
    def forward(self, x):
        return self.linear(x)

dummy = torch.randn(1, 1088)
torch.onnx.export(TinyClassifier(), dummy,
    "libs/classifier/src/test/resources/models/test_classifier.onnx",
    input_names=["input"],
    dynamic_axes={"input": {0: "batch"}})
```

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `EmbeddingModel` | **Mock** | Code embedding is tested in Stage 15; mock returns fixed 1024-dim vectors |
| `ZooModel` (DJL) | **Mock** (unit) / **Real** (integration) | Unit tests mock predictor; integration tests load test ONNX model |
| `Predictor` | **Mock** (unit) | Control classifier output probabilities for deterministic argmax testing |
| `ParsedClass` | **Test fixture records** | Hand-crafted parsed classes with known properties for feature extraction validation |
| `MinioStorageClient` | **Mock** (unit) / **Testcontainers** (integration) | Unit tests verify calls; integration tests verify persistence |
| `MeterRegistry` | **SimpleMeterRegistry** | In-memory registry for latency/counter verification |

### CI/CD Considerations

- Tag unit tests with `@Tag("unit")`, integration tests with `@Tag("integration")`
- ONNX model tests additionally tagged `@Tag("requires-onnx-models")` — these require running `scripts/generate_test_classifier.py` first
- Add a CI setup step: `python scripts/generate_test_classifier.py` to generate test ONNX models before the Gradle build
- Python training script validation: add a CI job that runs `tools/train_classifier.py` with synthetic data to verify the full training-to-export pipeline
- DJL ONNX Runtime native library download: set `DJL_CACHE_DIR` to a CI-cached directory to avoid repeated downloads
- Sealed interface `MigrationClassification` requires JDK 17+ — ensure CI runners use JDK 21 (same as production)
- Feature extractor tests are fast (no I/O) — run in parallel with `-Djunit.jupiter.execution.parallel.enabled=true`
- For the fallback integration test, use `@TestPropertySource` to point to a nonexistent model path, validating graceful degradation without additional infrastructure

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Feature extraction | Extract from Micronaut controller | 1088-dim vector (1024 + 64) |
| Reactive features | Class with 5 flatMap calls | reactiveOperatorCount = 5 |
| Framework features | Class with @Get, @Inject | annotationCount correct |
| ONNX model load | Load classifier.onnx | No error |
| Classify simple | POJO class | TRIVIAL difficulty |
| Classify reactive | Complex reactive class | HIGH difficulty |
| Classify Kafka | KafkaListener class | Kafka features detected |
| Confidence | Check output | Between 0 and 1 |
| Fallback | Model unavailable | Rule-based classification |
| Batch classify | 100 classes | All classified, summary computed |
| MinIO storage | Run classification | Results in evidence bucket |
| Metrics | Run batch | Per-difficulty counters populated |

## Files to create

- `libs/classifier/build.gradle.kts`
- `libs/classifier/src/main/java/com/flowforge/classifier/model/MigrationClassification.java`
- `libs/classifier/src/main/java/com/flowforge/classifier/feature/MigrationFeatureExtractor.java`
- `libs/classifier/src/main/java/com/flowforge/classifier/model/MigrationClassifier.java`
- `libs/classifier/src/main/java/com/flowforge/classifier/service/MigrationClassificationService.java`
- `libs/classifier/src/main/java/com/flowforge/classifier/training/TrainingDataGenerator.java`
- `tools/train_classifier.py`
- `tools/requirements-training.txt` (scikit-learn, skl2onnx, numpy)
- `libs/classifier/src/test/java/.../MigrationFeatureExtractorTest.java`
- `libs/classifier/src/test/java/.../MigrationClassifierTest.java`

## Depends on

- Stage 08 (parsed code artifacts)
- Stage 15 (code embedding model)

## Produces

- Per-class migration difficulty classifications
- Confidence scores + human-readable reasons
- Rule-based fallback when model unavailable
- Classification summary for snapshot
