package com.flowforge.parser.model;

import java.util.List;
import java.util.Optional;

/**
 * A class (or interface/enum/record) extracted from a Java file.
 */
public record ParsedClass(
    String fqn,
    String simpleName,
    String packageName,
    String filePath,
    ClassType classType,
    List<String> annotations,
    List<String> implementedInterfaces,
    Optional<String> superClass,
    List<ParsedMethod> methods,
    List<ParsedField> fields,
    List<String> imports,
    int lineStart,
    int lineEnd,
    String rawSource
) {
    public enum ClassType {
        CLASS,
        INTERFACE,
        ENUM,
        RECORD,
        ANNOTATION
    }
}
