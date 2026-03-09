package com.flowforge.classifier.classify;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.flowforge.classifier.model.MigrationClassification;
import com.flowforge.common.config.FlowForgeProperties;
import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import com.flowforge.classifier.feature.MigrationFeatureExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Classifies parsed classes for migration difficulty using DJL ONNX model with rule-based fallback.
 */
@Service
@ConditionalOnBean(name = "codeEmbeddingModel")
public class MigrationClassifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MigrationClassifier.class);

    private final ZooModel<NDList, NDList> classifierModel;
    private final MigrationFeatureExtractor featureExtractor;
    private final MeterRegistry meterRegistry;

    public MigrationClassifier(FlowForgeProperties props,
                               MigrationFeatureExtractor featureExtractor,
                               MeterRegistry meterRegistry) throws Exception {
        this.featureExtractor = featureExtractor;
        this.meterRegistry = meterRegistry;
        if (props.gnn() != null && props.gnn().classifierModelPath() != null
            && !props.gnn().classifierModelPath().isBlank()) {
            var criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(Path.of(props.gnn().classifierModelPath()))
                .optEngine("OnnxRuntime")
                .build();
            this.classifierModel = criteria.loadModel();
        } else {
            this.classifierModel = null;
        }
    }

    /**
     * Constructor for tests that inject a mock ZooModel.
     */
    public MigrationClassifier(ZooModel<NDList, NDList> classifierModel,
                               MigrationFeatureExtractor featureExtractor,
                               MeterRegistry meterRegistry) {
        this.classifierModel = classifierModel;
        this.featureExtractor = featureExtractor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Classify a parsed class for migration difficulty.
     */
    public MigrationClassification.ReactiveComplexityClass classifyReactiveComplexity(ParsedClass parsedClass) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (classifierModel != null) {
                try (var predictor = classifierModel.newPredictor();
                     var manager = NDManager.newBaseManager()) {
                    var features = featureExtractor.extractFeatures(parsedClass);
                    var input = new NDList(manager.create(features));
                    var output = predictor.predict(input);
                    var probs = output.get(0).toFloatArray();
                    int bestClass = argmax(probs);
                    var difficulty = MigrationClassification.MigrationDifficulty.values()[
                        Math.min(bestClass, MigrationClassification.MigrationDifficulty.values().length - 1)];
                    float confidence = bestClass < probs.length ? probs[bestClass] : 0.5f;
                    return new MigrationClassification.ReactiveComplexityClass(
                        parsedClass.fqn(),
                        difficulty,
                        confidence,
                        generateReasons(parsedClass, bestClass)
                    );
                }
            }
        } catch (Exception e) {
            log.error("Classification failed for {}", parsedClass.fqn(), e);
        } finally {
            sample.stop(meterRegistry.timer("flowforge.classifier.reactive.latency"));
        }
        return fallbackClassification(parsedClass);
    }

    /**
     * Rule-based fallback when model is unavailable. Package visibility for tests.
     */
    MigrationClassification.ReactiveComplexityClass fallbackClassification(ParsedClass parsedClass) {
        ReactiveComplexity maxComplexity = parsedClass.methods().stream()
            .map(ParsedMethod::reactiveComplexity)
            .filter(c -> c != null)
            .max(Comparator.naturalOrder())
            .orElse(ReactiveComplexity.NONE);

        var difficulty = switch (maxComplexity) {
            case NONE -> MigrationClassification.MigrationDifficulty.TRIVIAL;
            case LINEAR -> MigrationClassification.MigrationDifficulty.LOW;
            case BRANCHING -> MigrationClassification.MigrationDifficulty.MEDIUM;
            case COMPLEX -> MigrationClassification.MigrationDifficulty.HIGH;
        };

        return new MigrationClassification.ReactiveComplexityClass(
            parsedClass.fqn(),
            difficulty,
            0.5,
            List.of("Fallback: rule-based classification from reactive complexity")
        );
    }

    private static int argmax(float[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[best]) best = i;
        }
        return best;
    }

    private List<String> generateReasons(ParsedClass parsedClass, int difficultyOrdinal) {
        var difficulty = MigrationClassification.MigrationDifficulty.values()[
            Math.min(difficultyOrdinal, MigrationClassification.MigrationDifficulty.values().length - 1)];
        return List.of("Model prediction: " + difficulty.name());
    }

    @Override
    public void close() {
        if (classifierModel != null) {
            classifierModel.close();
        }
    }
}
