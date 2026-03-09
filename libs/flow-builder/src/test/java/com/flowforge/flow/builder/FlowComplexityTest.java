package com.flowforge.flow.builder;

import com.flowforge.flow.FlowBuilderTestFixtures;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.flow.model.FlowStep;
import com.flowforge.parser.model.ReactiveComplexity;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowComplexityTest {

    @Test
    void veryHigh_whenFiveOrMoreServicesInvolved() throws Exception {
        var steps = FlowBuilderTestFixtures.flowSteps("s1", "s2", "s3", "s4", "s5");
        assertThat(assessComplexity(steps)).isEqualTo(FlowCandidate.FlowComplexity.VERY_HIGH);
    }

    @Test
    void veryHigh_whenReactiveAndAsync() throws Exception {
        var steps = List.of(
            FlowBuilderTestFixtures.flowStep("svc-a", FlowStep.StepType.HTTP_ENDPOINT, ReactiveComplexity.BRANCHING),
            FlowBuilderTestFixtures.flowStep("svc-b", FlowStep.StepType.KAFKA_PRODUCE, ReactiveComplexity.NONE)
        );
        assertThat(assessComplexity(steps)).isEqualTo(FlowCandidate.FlowComplexity.VERY_HIGH);
    }

    @Test
    void medium_whenTwoServicesNoAsync() throws Exception {
        var steps = FlowBuilderTestFixtures.flowSteps("svc-a", "svc-b");
        assertThat(assessComplexity(steps)).isEqualTo(FlowCandidate.FlowComplexity.MEDIUM);
    }

    @Test
    void low_whenSingleService() throws Exception {
        var steps = FlowBuilderTestFixtures.flowSteps("svc-a");
        assertThat(assessComplexity(steps)).isEqualTo(FlowCandidate.FlowComplexity.LOW);
    }

    private static FlowCandidate.FlowComplexity assessComplexity(List<FlowStep> steps) throws Exception {
        var builder = new FlowCandidateBuilder(null, null, null, null);
        var method = FlowCandidateBuilder.class.getDeclaredMethod("assessComplexity", List.class);
        method.setAccessible(true);
        return (FlowCandidate.FlowComplexity) method.invoke(builder, steps);
    }
}
