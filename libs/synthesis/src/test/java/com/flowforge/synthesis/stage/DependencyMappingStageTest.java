package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.DependencyMappingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DependencyMappingStageTest {

    @Mock
    SynthesisStageExecutor executor;
    @Mock
    MinioStorageClient minio;

    DependencyMappingStage stage;

    @BeforeEach
    void setUp() {
        stage = new DependencyMappingStage(executor, minio, new ObjectMapper());
    }

    @Test
    void mapDependencies_passesStages1To3AsContext() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var expected = TestFixtures.sampleDependencyMappingOutput();
        when(executor.executeStage(eq("stage4"), eq(candidate),
            eq(DependencyMappingOutput.class), anyMap()))
            .thenReturn(expected);

        var result = stage.mapDependencies(candidate, partial);

        assertThat(result.runtimeDependencies()).isNotEmpty();
        verify(minio).putJson(eq("evidence"),
            ArgumentMatchers.argThat(path -> path != null && path.contains("synthesis/stage4/")),
            eq(expected));
    }

    @Test
    void mapDependencies_detectsVersionConflicts() {
        var candidate = TestFixtures.multiVersionFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var output = TestFixtures.dependencyOutputWithConflicts();
        when(executor.executeStage(eq("stage4"), eq(candidate),
            eq(DependencyMappingOutput.class), anyMap()))
            .thenReturn(output);

        var result = stage.mapDependencies(candidate, partial);

        assertThat(result.conflicts()).isNotEmpty();
        assertThat(result.conflicts().get(0).resolution()).isNotBlank();
    }
}
