package com.flowforge.flow.model;

import com.flowforge.parser.model.ReactiveComplexity;
import java.util.List;
import java.util.Optional;

public record FlowStep(
    int order,
    String serviceName,
    String action,
    StepType stepType,
    Optional<String> classFqn,
    Optional<String> methodName,
    Optional<String> kafkaTopic,
    List<String> annotations,
    ReactiveComplexity reactiveComplexity,
    Optional<String> errorHandling
) {
    public enum StepType {
        HTTP_ENDPOINT,
        HTTP_CLIENT_CALL,
        KAFKA_PRODUCE,
        KAFKA_CONSUME,
        DATABASE_QUERY,
        CACHE_LOOKUP,
        EXTERNAL_CALL
    }
}
