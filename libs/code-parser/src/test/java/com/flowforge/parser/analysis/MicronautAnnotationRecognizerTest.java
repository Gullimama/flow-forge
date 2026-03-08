package com.flowforge.parser.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.parser.model.ParsedField;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MicronautAnnotationRecognizerTest {

    private final MicronautAnnotationRecognizer recognizer = new MicronautAnnotationRecognizer();

    @Test
    void isEndpoint_httpAnnotation_returnsTrue() {
        var method = new ParsedMethod("get", "String", List.of(),
            List.of("io.micronaut.http.annotation.Get"), List.of(),
            false, ReactiveComplexity.NONE, List.of("GET"), Optional.empty(), 1, 5, "");

        assertThat(recognizer.isEndpoint(method)).isTrue();
    }

    @Test
    void isInjectionPoint_injectAnnotation_returnsTrue() {
        var field = new ParsedField("repo", "BookingRepository",
            List.of("jakarta.inject.Inject"), true);

        assertThat(recognizer.isInjectionPoint(field)).isTrue();
    }

    @Test
    void isMessageHandler_kafkaListener_returnsTrue() {
        var method = new ParsedMethod("onMessage", "void", List.of(),
            List.of("io.micronaut.configuration.kafka.annotation.KafkaListener"),
            List.of(), false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 1, 5, "");

        assertThat(recognizer.isMessageHandler(method)).isTrue();
    }

    @Test
    void isEndpoint_nonHttpAnnotation_returnsFalse() {
        var method = new ParsedMethod("process", "void", List.of(),
            List.of("Singleton"), List.of(),
            false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 1, 5, "");

        assertThat(recognizer.isEndpoint(method)).isFalse();
    }
}
