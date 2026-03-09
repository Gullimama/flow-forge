package com.flowforge.patterns.mining;

import com.flowforge.patterns.extract.CallSequenceExtractor.SequenceItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs SPMF PrefixSpan on extracted sequences. Uses reflection so the project compiles
 * without the SPMF JAR; when the JAR is on the classpath, mining runs normally.
 */
@Component
public class SequencePatternMiner {

    private static final Logger log = LoggerFactory.getLogger(SequencePatternMiner.class);
    private static final String SPMF_ALGO_CLASS = "ca.pfv.spmf.algorithms.sequentialpatterns.prefixSpan.AlgoPrefixSpan";

    private final int maxPatternLength;

    public SequencePatternMiner(double minSupport, int maxPatternLength) {
        this.maxPatternLength = maxPatternLength;
    }

    /**
     * Run PrefixSpan on extracted sequences. Returns empty list if SPMF is not on classpath.
     */
    public List<DiscoveredPattern> mine(
            List<List<SequenceItem>> sequences,
            double minSupportRatio) {

        if (sequences.isEmpty()) {
            return List.of();
        }

        var itemMap = new ConcurrentHashMap<SequenceItem, Integer>();
        var reverseMap = new ConcurrentHashMap<Integer, SequenceItem>();
        var idCounter = new AtomicInteger(0);

        var encodedSequences = new ArrayList<int[]>();
        for (var seq : sequences) {
            var encoded = new int[seq.size()];
            for (int i = 0; i < seq.size(); i++) {
                int id = seq.get(i).encode(itemMap, idCounter);
                encoded[i] = id;
                reverseMap.put(id, seq.get(i));
            }
            encodedSequences.add(encoded);
        }

        int minSupportAbs = Math.max(1, (int) Math.ceil(sequences.size() * minSupportRatio));
        try {
            var frequentSequences = runPrefixSpan(encodedSequences, minSupportAbs);
            return frequentSequences.stream()
                .map(fs -> decodePattern(fs, reverseMap, sequences.size()))
                .sorted(Comparator.comparingDouble(DiscoveredPattern::support).reversed())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("PrefixSpan file I/O failed", e);
        }
    }

    private List<FrequentSequence> runPrefixSpan(List<int[]> encodedSequences, int minSupport) throws IOException {
        Path inputFile = Files.createTempFile("spmf-input-", ".txt");
        Path outputFile = Files.createTempFile("spmf-output-", ".txt");
        try {
            try (var writer = Files.newBufferedWriter(inputFile)) {
                for (int[] sequence : encodedSequences) {
                    var sb = new StringBuilder();
                    for (int item : sequence) {
                        sb.append(item).append(" -1 ");
                    }
                    sb.append("-2");
                    writer.write(sb.toString());
                    writer.newLine();
                }
            }

            runPrefixSpanAlgo(inputFile.toString(), outputFile.toString(), minSupport);

            return Files.readAllLines(outputFile).stream()
                .filter(line -> !line.isBlank() && line.contains("#SUP:"))
                .map(this::parseOutputLine)
                .toList();
        } finally {
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
        }
    }

    @SuppressWarnings("java:S3011")
    private void runPrefixSpanAlgo(String inputPath, String outputPath, int minSupport) throws IOException {
        try {
            Class<?> algoClass = Class.forName(SPMF_ALGO_CLASS);
            Object algo = algoClass.getDeclaredConstructor().newInstance();
            Method setMax = algoClass.getMethod("setMaximumPatternLength", int.class);
            setMax.invoke(algo, maxPatternLength);
            Method run = algoClass.getMethod("runAlgorithm", String.class, String.class, int.class);
            run.invoke(algo, inputPath, outputPath, minSupport);
        } catch (ClassNotFoundException e) {
            log.warn("SPMF not on classpath (missing libs/spmf/spmf.jar); returning no patterns.");
        } catch (ReflectiveOperationException e) {
            throw new IOException("SPMF PrefixSpan invocation failed", e);
        }
    }

    private FrequentSequence parseOutputLine(String line) {
        String[] parts = line.split("#SUP:");
        int support = Integer.parseInt(parts[1].trim());
        String patternPart = parts[0].trim();
        int[] items = Arrays.stream(patternPart.split("\\s+"))
            .filter(s -> !s.equals("-1") && !s.equals("-2"))
            .mapToInt(Integer::parseInt)
            .toArray();
        return new FrequentSequence(items, support);
    }

    private record FrequentSequence(int[] items, int support) {}

    private DiscoveredPattern decodePattern(
            FrequentSequence frequent,
            Map<Integer, SequenceItem> reverseMap,
            int totalSequences) {

        var items = Arrays.stream(frequent.items())
            .mapToObj(reverseMap::get)
            .filter(java.util.Objects::nonNull)
            .toList();

        return new DiscoveredPattern(
            UUID.randomUUID(),
            items,
            frequent.support(),
            totalSequences > 0 ? (double) frequent.support() / totalSequences : 0.0,
            classifyPatternType(items)
        );
    }

    public static PatternType classifyPatternType(List<SequenceItem> items) {
        if (items.isEmpty()) return PatternType.NORMAL_FLOW;
        boolean allErrors = items.stream().allMatch(i -> "LOG_ERROR".equals(i.eventType()));
        if (allErrors && items.size() >= 2) {
            return items.stream().map(SequenceItem::serviceName).distinct().count() > 1
                ? PatternType.ERROR_PROPAGATION
                : PatternType.CASCADE_FAILURE;
        }
        boolean hasKafka = items.stream().anyMatch(i ->
            "KAFKA_PRODUCE".equals(i.eventType()) || "KAFKA_CONSUME".equals(i.eventType()));
        if (hasKafka && items.size() >= 2) return PatternType.ASYNC_FANOUT;

        int maxConsecutive = 0;
        int current = 1;
        for (int i = 1; i < items.size(); i++) {
            SequenceItem a = items.get(i - 1);
            SequenceItem b = items.get(i);
            if (a.serviceName().equals(b.serviceName())
                    && a.eventType().equals(b.eventType())
                    && a.detail().equals(b.detail())) {
                current++;
            } else {
                maxConsecutive = Math.max(maxConsecutive, current);
                current = 1;
            }
        }
        maxConsecutive = Math.max(maxConsecutive, current);
        if (maxConsecutive >= 3) return PatternType.RETRY_PATTERN;

        return PatternType.NORMAL_FLOW;
    }

    public record DiscoveredPattern(
        UUID patternId,
        List<SequenceItem> items,
        int absoluteSupport,
        double support,
        PatternType patternType
    ) {}

    public enum PatternType {
        NORMAL_FLOW,
        ERROR_PROPAGATION,
        RETRY_PATTERN,
        CASCADE_FAILURE,
        ASYNC_FANOUT
    }
}
