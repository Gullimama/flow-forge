package com.flowforge.classifier.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.flowforge.classifier.TestFixtures;
import com.flowforge.parser.model.ReactiveComplexity;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
class MigrationFeatureExtractorTest {

    @Mock
    EmbeddingModel codeEmbeddingModel;

    @Test
    void extractFeatures_returns1088DimVector() {
        var extractor = new MigrationFeatureExtractor(codeEmbeddingModel);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(new float[1024]);

        var features = extractor.extractFeatures(TestFixtures.micronautController());

        assertThat(features).hasSize(1088);
    }

    @Test
    void extractFeatures_countsReactiveOperators() {
        var extractor = new MigrationFeatureExtractor(codeEmbeddingModel);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(new float[1024]);
        var reactiveClass = TestFixtures.reactiveServiceWith(
            "flatMap", "switchMap", "concatMap", "zipWith", "retryWhen");

        var features = extractor.extractFeatures(reactiveClass);

        assertThat(features[1024]).isEqualTo(5.0f);
    }

    @Test
    void extractFeatures_countsFrameworkAnnotations() {
        var extractor = new MigrationFeatureExtractor(codeEmbeddingModel);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(new float[1024]);
        var annotatedClass = TestFixtures.classWithAnnotations(
            "@Controller", "@Get", "@Inject", "@Singleton");

        var features = extractor.extractFeatures(annotatedClass);

        assertThat(features[1025]).isEqualTo(4.0f);
    }

    @Test
    void extractFeatures_detectsReactiveReturnTypes() {
        var extractor = new MigrationFeatureExtractor(codeEmbeddingModel);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(new float[1024]);
        var monoClass = TestFixtures.classWithReactiveReturnTypes();

        var features = extractor.extractFeatures(monoClass);

        assertThat(features[1028]).isEqualTo(1.0f);
    }

    @Test
    void extractFeatures_detectsKafkaAnnotations() {
        var extractor = new MigrationFeatureExtractor(codeEmbeddingModel);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(new float[1024]);
        var kafkaClass = TestFixtures.kafkaListenerClass();

        var features = extractor.extractFeatures(kafkaClass);

        assertThat(features[1030]).isEqualTo(1.0f);
    }

    @Test
    void extractFeatures_computesMaxReactiveComplexity() {
        var extractor = new MigrationFeatureExtractor(codeEmbeddingModel);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(new float[1024]);
        var mixedClass = TestFixtures.classWithMixedMethods(
            ReactiveComplexity.NONE, ReactiveComplexity.BRANCHING, ReactiveComplexity.LINEAR);

        var features = extractor.extractFeatures(mixedClass);

        assertThat(features[1033]).isEqualTo(2.0f);
    }

    @Test
    void extractFeatures_concatenatesEmbeddingAndHandcrafted() {
        var extractor = new MigrationFeatureExtractor(codeEmbeddingModel);
        var embedding = new float[1024];
        Arrays.fill(embedding, 0.5f);
        when(codeEmbeddingModel.embed(anyString())).thenReturn(embedding);

        var features = extractor.extractFeatures(TestFixtures.simplePojo());

        for (int i = 0; i < 1024; i++) {
            assertThat(features[i]).isEqualTo(0.5f);
        }
    }
}
