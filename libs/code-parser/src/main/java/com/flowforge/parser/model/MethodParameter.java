package com.flowforge.parser.model;

import java.util.List;

/**
 * A single method parameter with name, type, and annotations.
 */
public record MethodParameter(String name, String type, List<String> annotations) {}
