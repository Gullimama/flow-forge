package com.flowforge.classifier.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.parser.analysis.ReactiveChainAnalyzer;
import com.flowforge.parser.service.MicronautCodeVisitorBridge;
import com.flowforge.parser.visitor.MicronautCodeVisitor;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrainingDataGeneratorTest {

    @Mock
    com.flowforge.classifier.feature.MigrationFeatureExtractor featureExtractor;

    TrainingDataGenerator generator;

    @BeforeEach
    void setUp() {
        var javaParser = new JavaParser(new ParserConfiguration());
        var visitor = new MicronautCodeVisitor(new ReactiveChainAnalyzer());
        var visitorBridge = new MicronautCodeVisitorBridge(visitor);
        generator = new TrainingDataGenerator(
            featureExtractor,
            new ObjectMapper(),
            javaParser,
            visitorBridge);
    }

    @Test
    void generate_producesCorrectNumberOfSamples(@TempDir Path tempDir) throws Exception {
        var labelledData = tempDir.resolve("labelled.jsonl");
        Files.writeString(labelledData,
            "{\"source_path\":\"Foo.java\",\"difficulty\":\"HIGH\",\"source\":\"class Foo {}\"}\n"
                + "{\"source_path\":\"Bar.java\",\"difficulty\":\"LOW\",\"source\":\"class Bar {}\"}");

        when(featureExtractor.extractFeatures(any())).thenReturn(new float[1088]);

        var outputPath = tempDir.resolve("training-data.json");
        generator.generate(labelledData, outputPath);

        assertThat(outputPath).exists();
        var read = new ObjectMapper().readTree(outputPath.toFile());
        assertThat(read.has("features")).isTrue();
        assertThat(read.has("labels")).isTrue();
        assertThat(read.get("features").size()).isEqualTo(2);
        assertThat(read.get("labels").size()).isEqualTo(2);
    }
}
