package com.flowforge.classifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowforge.classifier.TestFixtures;
import com.flowforge.classifier.model.MigrationClassification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationClassificationServiceTest {

    @Mock
    com.flowforge.classifier.classify.MigrationClassifier classifier;
    @Mock
    com.flowforge.common.client.MinioStorageClient minio;
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    MigrationClassificationService service;

    @BeforeEach
    void setUp() {
        service = new MigrationClassificationService(classifier, minio, meterRegistry);
    }

    @Test
    void classifySnapshot_classifiesAllClasses() {
        var classes = List.of(
            TestFixtures.simplePojo(),
            TestFixtures.micronautController(),
            TestFixtures.kafkaListenerClass());
        when(classifier.classifyReactiveComplexity(any()))
            .thenReturn(TestFixtures.classificationResult(MigrationClassification.MigrationDifficulty.LOW));

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
            .thenReturn(TestFixtures.classificationResult(MigrationClassification.MigrationDifficulty.TRIVIAL));
        when(classifier.classifyReactiveComplexity(classes.get(1)))
            .thenReturn(TestFixtures.classificationResult(MigrationClassification.MigrationDifficulty.HIGH));

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
            .thenReturn(TestFixtures.classificationResult(MigrationClassification.MigrationDifficulty.LOW));

        service.classifySnapshot(snapshotId, classes);

        verify(minio).putJson(eq("evidence"),
            eq("classification/" + snapshotId + ".json"), any());
    }
}
