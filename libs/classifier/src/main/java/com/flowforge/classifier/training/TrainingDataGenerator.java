package com.flowforge.classifier.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.classifier.feature.MigrationFeatureExtractor;
import com.flowforge.classifier.model.MigrationClassification;
import com.flowforge.parser.model.ParsedClass;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Generates training data from labelled JSONL (source + difficulty) into a JSON file with features and labels.
 */
@Component
@ConditionalOnBean(JavaParser.class)
public class TrainingDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(TrainingDataGenerator.class);

    private final MigrationFeatureExtractor featureExtractor;
    private final ObjectMapper objectMapper;
    private final JavaParser javaParser;
    private final com.flowforge.parser.service.MicronautCodeVisitorBridge visitorBridge;

    public TrainingDataGenerator(MigrationFeatureExtractor featureExtractor,
                                 ObjectMapper objectMapper,
                                 JavaParser javaParser,
                                 com.flowforge.parser.service.MicronautCodeVisitorBridge visitorBridge) {
        this.featureExtractor = featureExtractor;
        this.objectMapper = objectMapper;
        this.javaParser = javaParser;
        this.visitorBridge = visitorBridge;
    }

    /**
     * Read labelled JSONL and write features + labels JSON.
     * Input: one JSON object per line with "source_path", "difficulty", "source".
     * Output: JSON with "features" (array of float arrays) and "labels" (array of int).
     */
    public void generate(Path labelledDataPath, Path outputPath) throws Exception {
        var lines = Files.readAllLines(labelledDataPath);
        var features = new float[lines.size()][];
        var labels = new int[lines.size()];

        for (int i = 0; i < lines.size(); i++) {
            var entry = objectMapper.readTree(lines.get(i));
            ParsedClass parsed = parseSource(entry.get("source").asText());
            features[i] = featureExtractor.extractFeatures(parsed);
            labels[i] = MigrationClassification.MigrationDifficulty
                .valueOf(entry.get("difficulty").asText()).ordinal();
        }

        var output = Map.of("features", (Object) features, "labels", labels);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), output);

        log.info("Generated training data: {} samples, {} features",
            features.length, features.length > 0 ? features[0].length : 0);
    }

    private ParsedClass parseSource(String source) {
        ParseResult<CompilationUnit> result = javaParser.parse(
            ParseStart.COMPILATION_UNIT,
            Providers.provider(source)
        );
        if (result.isSuccessful() && result.getResult().isPresent()) {
            var classes = new ArrayList<ParsedClass>();
            visitorBridge.visit(result.getResult().get(), Path.of("Generated.java"), classes);
            if (!classes.isEmpty()) {
                return classes.get(0);
            }
        }
        return minimalParsedClass(source);
    }

    private static ParsedClass minimalParsedClass(String source) {
        return new ParsedClass(
            "Unknown",
            "Unknown",
            "",
            "Generated.java",
            ParsedClass.ClassType.CLASS,
            List.of(),
            List.of(),
            java.util.Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            1,
            (int) Math.max(1, source.lines().count()),
            source
        );
    }
}
