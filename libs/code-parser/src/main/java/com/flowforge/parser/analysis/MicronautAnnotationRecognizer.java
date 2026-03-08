package com.flowforge.parser.analysis;

import com.flowforge.parser.model.ParsedField;
import com.flowforge.parser.model.ParsedMethod;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Recognizes Micronaut-style annotations for HTTP endpoints, injection, and messaging.
 */
@Component
public class MicronautAnnotationRecognizer {

    private static final Set<String> HTTP_METHOD_ANNOTATIONS = Set.of(
        "Get", "Post", "Put", "Delete", "Patch", "Options", "Head",
        "HttpMethodMapping"
    );

    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
        "Inject", "Value", "Property", "Named", "Singleton",
        "Prototype", "RequestScope", "Context"
    );

    private static final Set<String> MESSAGING_ANNOTATIONS = Set.of(
        "KafkaListener", "KafkaClient", "Topic",
        "RabbitListener", "RabbitClient"
    );

    public boolean isEndpoint(ParsedMethod method) {
        return method.annotations().stream()
            .anyMatch(a -> HTTP_METHOD_ANNOTATIONS.contains(stripPackage(a)));
    }

    public boolean isInjectionPoint(ParsedField field) {
        return field.annotations().stream()
            .anyMatch(a -> INJECTION_ANNOTATIONS.contains(stripPackage(a)));
    }

    public boolean isMessageHandler(ParsedMethod method) {
        return method.annotations().stream()
            .anyMatch(a -> MESSAGING_ANNOTATIONS.contains(stripPackage(a)));
    }

    private static String stripPackage(String annotation) {
        if (annotation == null) {
            return "";
        }
        int lastDot = annotation.lastIndexOf('.');
        return lastDot >= 0 ? annotation.substring(lastDot + 1) : annotation;
    }
}
