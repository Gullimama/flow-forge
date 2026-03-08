package com.flowforge.parser.analysis;

import com.flowforge.parser.model.ReactiveComplexity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Analyzes a method body for reactive (e.g. Reactor/Mono/Flux) chain complexity.
 */
@Component
public class ReactiveChainAnalyzer {

    private static final Set<String> REACTIVE_OPERATORS = Set.of(
        "flatMap", "map", "filter", "switchMap", "concatMap",
        "zipWith", "mergeWith", "then", "thenReturn",
        "onErrorResume", "onErrorReturn", "onErrorMap",
        "subscribe", "block", "toFuture",
        "doOnNext", "doOnError", "doOnComplete",
        "publishOn", "subscribeOn", "parallel"
    );

    private static final Set<String> BRANCHING_OPERATORS = Set.of(
        "switchIfEmpty", "switchMap", "zipWith", "mergeWith",
        "onErrorResume", "retry", "retryWhen"
    );

    /**
     * Analyze a method body for reactive chain complexity.
     */
    public ReactiveComplexity analyze(MethodDeclaration method) {
        List<String> calls = new ArrayList<>();
        method.walk(MethodCallExpr.class, call -> calls.add(call.getNameAsString()));

        long reactiveCount = calls.stream()
            .filter(REACTIVE_OPERATORS::contains)
            .count();

        if (reactiveCount == 0) {
            return ReactiveComplexity.NONE;
        }

        long branchingCount = calls.stream()
            .filter(BRANCHING_OPERATORS::contains)
            .count();

        if (branchingCount >= 2 || reactiveCount >= 6) {
            return ReactiveComplexity.COMPLEX;
        }
        if (branchingCount >= 1) {
            return ReactiveComplexity.BRANCHING;
        }
        return ReactiveComplexity.LINEAR;
    }
}
