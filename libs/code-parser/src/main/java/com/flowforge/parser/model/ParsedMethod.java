package com.flowforge.parser.model;

import java.util.List;
import java.util.Optional;

/**
 * A method extracted from a class (signature, annotations, reactive info, HTTP info).
 */
public record ParsedMethod(
    String name,
    String returnType,
    List<MethodParameter> parameters,
    List<String> annotations,
    List<String> thrownExceptions,
    boolean isReactive,
    ReactiveComplexity reactiveComplexity,
    List<String> httpMethods,
    Optional<String> httpPath,
    int lineStart,
    int lineEnd,
    String rawSource
) {}
