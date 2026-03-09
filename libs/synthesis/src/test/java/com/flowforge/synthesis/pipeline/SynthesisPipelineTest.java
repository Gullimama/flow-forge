package com.flowforge.synthesis.pipeline;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.service.SynthesisStages1To3Service;
import com.flowforge.synthesis.service.SynthesisStages4To6Service;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SynthesisPipelineTest {

    @Mock
    SynthesisStages1To3Service stages1to3;
    @Mock
    SynthesisStages4To6Service stages4to6;
    @Mock
    MinioStorageClient minio;

    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SynthesisPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new SynthesisPipeline(stages1to3, stages4to6, minio, meterRegistry);
    }

    @Test
    void synthesize_runsFullPipelineForAllCandidates() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate());
        var partial = TestFixtures.samplePartialResult();
        var full = TestFixtures.sampleFullResult();

        when(stages1to3.runStages1To3(any())).thenReturn(partial);
        when(stages4to6.runStages4To6(any(), eq(partial))).thenReturn(full);

        var results = pipeline.synthesize(UUID.randomUUID(), candidates);

        assertThat(results).hasSize(2);
        verify(minio).putJson(eq("evidence"), ArgumentMatchers.argThat(s -> s != null && s.contains("synthesis/complete/")), eq(results));
    }

    @Test
    void synthesize_checkpointsPartialFailureToMinio() {
        var snapshotId = UUID.randomUUID();
        var candidates = List.of(TestFixtures.httpFlowCandidate());

        when(stages1to3.runStages1To3(any()))
            .thenThrow(new RuntimeException("LLM timeout on stage 2"));

        var results = pipeline.synthesize(snapshotId, candidates);

        assertThat(results).isEmpty();
        verify(minio).putJson(eq("evidence"),
            ArgumentMatchers.argThat(path -> path != null && path.contains("synthesis/partial/" + snapshotId)),
            ArgumentMatchers.argThat(map -> map instanceof java.util.Map && ((java.util.Map<?, ?>) map).containsKey("error")));
    }

    @Test
    void synthesize_continuesAfterOneFlowFails() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate());

        when(stages1to3.runStages1To3(candidates.get(0)))
            .thenThrow(new RuntimeException("Stage 1 failure"));
        when(stages1to3.runStages1To3(candidates.get(1)))
            .thenReturn(TestFixtures.samplePartialResult());
        when(stages4to6.runStages4To6(any(), any()))
            .thenReturn(TestFixtures.sampleFullResult());

        var results = pipeline.synthesize(UUID.randomUUID(), candidates);

        assertThat(results).hasSize(1);
    }
}
