package com.flowforge.classifier.service;

import com.flowforge.classifier.classify.MigrationClassifier;
import com.flowforge.classifier.model.MigrationClassification;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.parser.model.ParsedClass;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Classifies all code artifacts in a snapshot and stores results in MinIO.
 */
@Service
@ConditionalOnBean(MigrationClassifier.class)
public class MigrationClassificationService {

    private final MigrationClassifier classifier;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    public MigrationClassificationService(MigrationClassifier classifier,
                                          MinioStorageClient minio,
                                          MeterRegistry meterRegistry) {
        this.classifier = classifier;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

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

        minio.putJson("evidence", "classification/" + snapshotId + ".json",
            Map.of("classifications", classifications, "summary", byDifficulty));

        meterRegistry.counter("flowforge.classification.total").increment(classifications.size());
        byDifficulty.forEach((diff, count) ->
            meterRegistry.counter("flowforge.classification.difficulty", "level", diff.name())
                .increment(count));

        return new ClassificationResult(classifications, byDifficulty);
    }

    public record ClassificationResult(
        List<MigrationClassification.ReactiveComplexityClass> classifications,
        Map<MigrationClassification.MigrationDifficulty, Long> byDifficulty
    ) {}
}
