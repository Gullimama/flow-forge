package com.flowforge.classifier.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.ZooModel;
import com.flowforge.classifier.TestFixtures;
import com.flowforge.classifier.model.MigrationClassification;
import com.flowforge.parser.model.ReactiveComplexity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationClassifierTest {

    @Mock
    ZooModel<NDList, NDList> classifierModel;
    @Mock
    com.flowforge.classifier.feature.MigrationFeatureExtractor featureExtractor;
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    MigrationClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new MigrationClassifier(classifierModel, featureExtractor, meterRegistry);
    }

    @Test
    void classifyReactiveComplexity_returnsCorrectDifficulty() throws Exception {
        var parsedClass = TestFixtures.micronautController();
        when(featureExtractor.extractFeatures(parsedClass)).thenReturn(new float[1088]);
        var predictor = mock(ai.djl.inference.Predictor.class);
        when(classifierModel.newPredictor()).thenReturn(predictor);
        try (var manager = NDManager.newBaseManager()) {
            var output = TestFixtures.classifierOutput(manager, new float[]{0.05f, 0.1f, 0.15f, 0.6f, 0.1f});
            when(predictor.predict(any())).thenReturn(output);

            var result = classifier.classifyReactiveComplexity(parsedClass);

            assertThat(result.difficulty()).isEqualTo(MigrationClassification.MigrationDifficulty.HIGH);
            assertThat(result.confidence()).isCloseTo(0.6, org.assertj.core.api.Assertions.within(0.01));
            assertThat(result.classFqn()).isEqualTo(parsedClass.fqn());
        }
    }

    @Test
    void classifyReactiveComplexity_fallsBackToRulesOnModelFailure() throws Exception {
        var parsedClass = TestFixtures.reactiveServiceWith("flatMap");
        when(featureExtractor.extractFeatures(parsedClass)).thenReturn(new float[1088]);
        var predictor = mock(ai.djl.inference.Predictor.class);
        when(classifierModel.newPredictor()).thenReturn(predictor);
        when(predictor.predict(any())).thenThrow(new RuntimeException("ONNX inference error"));

        var result = classifier.classifyReactiveComplexity(parsedClass);

        assertThat(result.confidence()).isEqualTo(0.5);
        assertThat(result.reasons()).anyMatch(r -> r.contains("Fallback: rule-based classification from reactive complexity"));
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
