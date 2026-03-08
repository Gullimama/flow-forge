package com.flowforge.parser.visitor;

import com.flowforge.parser.analysis.ReactiveChainAnalyzer;
import com.flowforge.parser.model.MethodParameter;
import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedField;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Visits Java AST and extracts ParsedClass (and inner classes), methods, fields, HTTP/metadata.
 */
@Component
public class MicronautCodeVisitor extends VoidVisitorAdapter<List<ParsedClass>> {

    private static final Set<String> HTTP_METHOD_ANNOTATIONS = Set.of(
        "Get", "Post", "Put", "Delete", "Patch", "Options", "Head");

    private final ReactiveChainAnalyzer reactiveAnalyzer;

    /** Set by caller before accept() to resolve file path for parsed classes. */
    private String currentFilePath = "";

    public MicronautCodeVisitor(ReactiveChainAnalyzer reactiveAnalyzer) {
        this.reactiveAnalyzer = reactiveAnalyzer;
    }

    public void setCurrentFilePath(String path) {
        this.currentFilePath = path != null ? path : "";
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration n, List<ParsedClass> collector) {
        var methods = n.getMethods().stream()
            .map(this::parseMethod)
            .toList();

        var fields = n.getFields().stream()
            .flatMap(f -> f.getVariables().stream().map(v -> parseField(v, f)))
            .toList();

        ParsedClass parsed = new ParsedClass(
            resolveFqn(n),
            n.getNameAsString(),
            resolvePackage(n),
            currentFilePath,
            n.isInterface() ? ParsedClass.ClassType.INTERFACE : ParsedClass.ClassType.CLASS,
            extractAnnotations(n),
            n.getImplementedTypes().stream().map(t -> t.asString()).toList(),
            n.getExtendedTypes().isEmpty() ? Optional.empty() : Optional.of(n.getExtendedTypes().get(0).asString()),
            methods,
            fields,
            extractImports(n),
            n.getBegin().map(p -> p.line).orElse(0),
            n.getEnd().map(p -> p.line).orElse(0),
            n.toString()
        );
        collector.add(parsed);

        super.visit(n, collector);
    }

    @Override
    public void visit(EnumDeclaration n, List<ParsedClass> collector) {
        var methods = n.getMethods().stream()
            .map(this::parseMethod)
            .toList();

        var fields = n.getEntries().stream()
            .map(e -> new ParsedField(e.getNameAsString(), "enum constant", List.of(), false))
            .toList();
        var fieldDecls = n.getFields().stream()
            .flatMap(f -> f.getVariables().stream().map(v -> parseField(v, f)))
            .toList();
        var allFields = new ArrayList<ParsedField>(fields);
        allFields.addAll(fieldDecls);

        ParsedClass parsed = new ParsedClass(
            resolveFqnEnum(n),
            n.getNameAsString(),
            resolvePackageFromParent(n),
            currentFilePath,
            ParsedClass.ClassType.ENUM,
            extractAnnotations(n),
            List.of(),
            Optional.empty(),
            methods,
            allFields,
            extractImportsFromParent(n),
            n.getBegin().map(p -> p.line).orElse(0),
            n.getEnd().map(p -> p.line).orElse(0),
            n.toString()
        );
        collector.add(parsed);

        super.visit(n, collector);
    }

    private String resolveFqn(ClassOrInterfaceDeclaration n) {
        String pkg = resolvePackage(n);
        return pkg.isEmpty() ? n.getNameAsString() : pkg + "." + n.getNameAsString();
    }

    private String resolveFqnEnum(EnumDeclaration n) {
        String pkg = resolvePackageFromParent(n);
        return pkg.isEmpty() ? n.getNameAsString() : pkg + "." + n.getNameAsString();
    }

    private String resolvePackage(ClassOrInterfaceDeclaration n) {
        return n.findAncestor(CompilationUnit.class)
            .flatMap(cu -> cu.getPackageDeclaration().map(pd -> pd.getNameAsString()))
            .orElse("");
    }

    private String resolvePackageFromParent(EnumDeclaration n) {
        return n.findAncestor(CompilationUnit.class)
            .flatMap(cu -> cu.getPackageDeclaration().map(pd -> pd.getNameAsString()))
            .orElse("");
    }

    private List<String> extractImports(ClassOrInterfaceDeclaration n) {
        return extractImportsFromCompilationUnit(n.findAncestor(CompilationUnit.class));
    }

    private List<String> extractImportsFromParent(EnumDeclaration n) {
        return extractImportsFromCompilationUnit(n.findAncestor(CompilationUnit.class));
    }

    private List<String> extractImportsFromCompilationUnit(Optional<CompilationUnit> cuOpt) {
        if (cuOpt.isEmpty()) {
            return List.of();
        }
        return cuOpt.get().getImports().stream()
            .map(i -> i.getNameAsString())
            .toList();
    }

    private List<String> extractAnnotations(ClassOrInterfaceDeclaration n) {
        return n.getAnnotations().stream()
            .map(this::annotationToString)
            .toList();
    }

    private List<String> extractAnnotations(EnumDeclaration n) {
        return n.getAnnotations().stream()
            .map(this::annotationToString)
            .toList();
    }

    private List<String> extractAnnotations(MethodDeclaration m) {
        return m.getAnnotations().stream()
            .map(this::annotationToString)
            .toList();
    }

    private List<String> extractAnnotations(FieldDeclaration f) {
        return f.getAnnotations().stream()
            .map(this::annotationToString)
            .toList();
    }

    private String annotationToString(AnnotationExpr a) {
        String name = a.getNameAsString();
        for (var child : a.getChildNodes()) {
            if (child instanceof MemberValuePair pair && "value".equals(pair.getNameAsString())) {
                var opt = pair.getValue().toStringLiteralExpr();
                if (opt.isPresent()) {
                    return name + "(\"" + opt.get().getValue() + "\")";
                }
            }
        }
        return name;
    }

    private List<MethodParameter> extractParameters(MethodDeclaration m) {
        return m.getParameters().stream()
            .map(this::toMethodParameter)
            .toList();
    }

    private MethodParameter toMethodParameter(Parameter p) {
        return new MethodParameter(
            p.getNameAsString(),
            p.getType().asString(),
            p.getAnnotations().stream().map(this::annotationToString).toList()
        );
    }

    private List<String> extractThrown(MethodDeclaration m) {
        return m.getThrownExceptions().stream()
            .map(e -> e.asString())
            .toList();
    }

    private List<String> extractHttpMethods(MethodDeclaration m) {
        List<String> out = new ArrayList<>();
        for (AnnotationExpr a : m.getAnnotations()) {
            String simple = a.getNameAsString();
            if (HTTP_METHOD_ANNOTATIONS.contains(simple)) {
                out.add(simple.toUpperCase());
            }
        }
        return out;
    }

    private Optional<String> extractHttpPath(MethodDeclaration m) {
        for (AnnotationExpr a : m.getAnnotations()) {
            String simple = a.getNameAsString();
            if (HTTP_METHOD_ANNOTATIONS.contains(simple)) {
                if (a instanceof SingleMemberAnnotationExpr single) {
                    var opt = single.getMemberValue().toStringLiteralExpr();
                    if (opt.isPresent()) {
                        return Optional.of(opt.get().getValue());
                    }
                }
                for (var child : a.getChildNodes()) {
                    if (child instanceof MemberValuePair pair && "value".equals(pair.getNameAsString())) {
                        var opt = pair.getValue().toStringLiteralExpr();
                        if (opt.isPresent()) {
                            return Optional.of(opt.get().getValue());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean isInjected(FieldDeclaration f) {
        Set<String> inject = Set.of("Inject", "Value", "Property", "Named");
        return f.getAnnotations().stream()
            .anyMatch(a -> inject.contains(a.getNameAsString()));
    }

    private ParsedMethod parseMethod(MethodDeclaration m) {
        ReactiveComplexity reactive = reactiveAnalyzer.analyze(m);
        return new ParsedMethod(
            m.getNameAsString(),
            m.getType().asString(),
            extractParameters(m),
            extractAnnotations(m),
            extractThrown(m),
            reactive != ReactiveComplexity.NONE,
            reactive,
            extractHttpMethods(m),
            extractHttpPath(m),
            m.getBegin().map(p -> p.line).orElse(0),
            m.getEnd().map(p -> p.line).orElse(0),
            m.toString()
        );
    }

    private ParsedField parseField(VariableDeclarator v, FieldDeclaration f) {
        return new ParsedField(
            v.getNameAsString(),
            v.getType().asString(),
            extractAnnotations(f),
            isInjected(f)
        );
    }
}
