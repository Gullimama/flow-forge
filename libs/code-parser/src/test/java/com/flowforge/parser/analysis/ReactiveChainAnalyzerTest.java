package com.flowforge.parser.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.parser.model.ReactiveComplexity;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;

class ReactiveChainAnalyzerTest {

    private final ReactiveChainAnalyzer analyzer = new ReactiveChainAnalyzer();

    @Test
    void analyze_noReactiveOperators_returnsNone() {
        var method = parseMethod("""
            public String getName() { return this.name; }
            """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.NONE);
    }

    @Test
    void analyze_linearChain_returnsLinear() {
        var method = parseMethod("""
            public Mono<String> fetch() {
                return repository.findById(id)
                        .map(Entity::getName)
                        .filter(n -> !n.isBlank());
            }
            """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.LINEAR);
    }

    @Test
    void analyze_branchingChain_returnsBranching() {
        var method = parseMethod("""
            public Mono<String> fetch() {
                return repository.findById(id)
                        .flatMap(this::transform)
                        .switchIfEmpty(Mono.just("default"))
                        .map(String::toUpperCase);
            }
            """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.BRANCHING);
    }

    @Test
    void analyze_complexChain_returnsComplex() {
        var method = parseMethod("""
            public Mono<Result> process() {
                return repo.findById(id)
                        .flatMap(this::validate)
                        .switchIfEmpty(createNew())
                        .zipWith(configService.getConfig())
                        .flatMap(t -> transform(t.getT1(), t.getT2()))
                        .onErrorResume(this::handleError)
                        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
            }
            """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.COMPLEX);
    }

    private static MethodDeclaration parseMethod(String methodBody) {
        String source = "public class C { " + methodBody + " }";
        JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_11));
        ParseResult<CompilationUnit> result = parser.parse(
            ParseStart.COMPILATION_UNIT, Providers.provider(source));
        assertThat(result.isSuccessful()).isTrue();
        return result.getResult().orElseThrow()
            .getClassByName("C").orElseThrow()
            .getMethods().get(0);
    }
}
