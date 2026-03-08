package com.flowforge.parser.service;

import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.visitor.MicronautCodeVisitor;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sets the current file path on the visitor and delegates visit so the service does not depend on visitor internals.
 */
@Component
public class MicronautCodeVisitorBridge {

    private final MicronautCodeVisitor visitor;

    public MicronautCodeVisitorBridge(MicronautCodeVisitor visitor) {
        this.visitor = visitor;
    }

    public void visit(CompilationUnit cu, Path file, List<ParsedClass> classes) {
        visitor.setCurrentFilePath(file != null ? file.toString() : "");
        cu.accept(visitor, classes);
    }
}
