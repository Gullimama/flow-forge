package com.flowforge.classifier.feature;

import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedField;
import com.flowforge.parser.model.ParsedMethod;
import java.util.List;
import java.util.Set;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Extracts classification features from a parsed class: code embedding + handcrafted features.
 */
@Component
public class MigrationFeatureExtractor {

    private static final int HANDCRAFTED_DIM = 64;
    private static final Set<String> REACTIVE_OPERATORS = Set.of(
        "flatMap", "switchMap", "concatMap", "zipWith", "retryWhen",
        "map", "filter", "doOnNext", "then", "transform", "block", "subscribe"
    );

    private final EmbeddingModel codeEmbeddingModel;

    public MigrationFeatureExtractor(@Qualifier("codeEmbeddingModel") EmbeddingModel codeEmbeddingModel) {
        this.codeEmbeddingModel = codeEmbeddingModel;
    }

    /**
     * Extract classification features: embedding (1024) + handcrafted (64) = 1088 dims.
     */
    public float[] extractFeatures(ParsedClass parsedClass) {
        String codeText = parsedClass.rawSource() != null ? parsedClass.rawSource() : "";
        float[] embedding = codeEmbeddingModel.embed(codeText);
        if (embedding == null) {
            embedding = new float[1024];
        }

        float[] handcrafted = new float[HANDCRAFTED_DIM];
        int idx = 0;

        handcrafted[idx++] = countReactiveOperators(parsedClass);
        handcrafted[idx++] = countFrameworkAnnotations(parsedClass);
        handcrafted[idx++] = (float) parsedClass.fields().stream().filter(ParsedField::isInjected).count();
        handcrafted[idx++] = (float) parsedClass.methods().size();
        handcrafted[idx++] = parsedClass.methods().stream().anyMatch(m -> isReactiveReturnType(m.returnType())) ? 1.0f : 0.0f;
        handcrafted[idx++] = (float) parsedClass.methods().stream().filter(m -> m.httpMethods() != null && !m.httpMethods().isEmpty()).count();
        handcrafted[idx++] = parsedClass.methods().stream().anyMatch(m ->
            m.annotations() != null && m.annotations().stream().anyMatch(a -> a != null && a.contains("Kafka"))) ? 1.0f : 0.0f;
        handcrafted[idx++] = (float) (parsedClass.implementedInterfaces() != null ? parsedClass.implementedInterfaces().size() : 0);
        handcrafted[idx++] = (float) Math.max(0, parsedClass.lineEnd() - parsedClass.lineStart());
        handcrafted[idx++] = (float) parsedClass.methods().stream()
            .mapToInt(m -> m.reactiveComplexity() != null ? m.reactiveComplexity().ordinal() : 0)
            .max().orElse(0);

        var combined = new float[embedding.length + handcrafted.length];
        System.arraycopy(embedding, 0, combined, 0, embedding.length);
        System.arraycopy(handcrafted, 0, combined, embedding.length, handcrafted.length);
        return combined;
    }

    private float countReactiveOperators(ParsedClass parsedClass) {
        String source = parsedClass.rawSource();
        if (source == null) return 0f;
        int count = 0;
        for (String op : REACTIVE_OPERATORS) {
            int i = 0;
            while ((i = source.indexOf(op, i)) >= 0) {
                count++;
                i += op.length();
            }
        }
        return (float) count;
    }

    private float countFrameworkAnnotations(ParsedClass parsedClass) {
        int count = 0;
        if (parsedClass.annotations() != null) {
            count += parsedClass.annotations().size();
        }
        if (parsedClass.methods() != null) {
            for (ParsedMethod m : parsedClass.methods()) {
                if (m.annotations() != null) {
                    count += m.annotations().size();
                }
            }
        }
        return (float) count;
    }

    private boolean isReactiveReturnType(String returnType) {
        if (returnType == null || returnType.isBlank()) return false;
        String t = returnType.trim();
        return t.contains("Mono") || t.contains("Flux") || t.contains("Publisher")
            || t.contains("Single") || t.contains("Observable") || t.contains("Maybe");
    }
}
