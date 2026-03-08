package com.flowforge.parser.model;

import java.util.List;

/**
 * A field extracted from a class (name, type, annotations, injection marker).
 */
public record ParsedField(
    String name,
    String type,
    List<String> annotations,
    boolean isInjected
) {}
