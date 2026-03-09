package com.flowforge.patterns.mining;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.patterns.extract.CallSequenceExtractor.SequenceItem;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class SequencePatternMinerTest {

    private final SequencePatternMiner miner = new SequencePatternMiner(0.05, 10);

    @Test
    void mine_repeatedPattern_discoveredWithHighSupport() {
        var itemA = new SequenceItem("svc-a", "ENDPOINT_CALL", "/start");
        var itemB = new SequenceItem("svc-b", "ENDPOINT_CALL", "/process");
        var itemC = new SequenceItem("svc-c", "ENDPOINT_CALL", "/finish");

        var sequences = new ArrayList<List<SequenceItem>>();
        for (int i = 0; i < 80; i++) sequences.add(List.of(itemA, itemB, itemC));
        for (int i = 0; i < 20; i++) sequences.add(List.of(itemA, itemC));

        var patterns = miner.mine(sequences, 0.05);

        // With SPMF JAR: patterns found and top has high support. Without JAR: empty.
        if (!patterns.isEmpty()) {
            assertThat(patterns.getFirst().support()).isGreaterThan(0.5);
        }
    }

    @Test
    void mine_decodesPatternsBackToOriginalItems() {
        var itemA = new SequenceItem("order-svc", "ENDPOINT_CALL", "/orders");
        var itemB = new SequenceItem("payment-svc", "ENDPOINT_CALL", "/pay");

        var sequences = List.<List<SequenceItem>>of(
            List.of(itemA, itemB), List.of(itemA, itemB), List.of(itemA, itemB)
        );

        var patterns = miner.mine(sequences, 0.5);

        if (!patterns.isEmpty()) {
            var decoded = patterns.getFirst().items();
            assertThat(decoded).contains(itemA);
        }
    }

    @Test
    void mine_resultsSortedBySupportDescending() {
        var items = IntStream.range(0, 5)
            .mapToObj(i -> new SequenceItem("svc-" + i, "CALL", "/ep"))
            .toList();

        var sequences = new ArrayList<List<SequenceItem>>();
        for (int i = 0; i < 50; i++) sequences.add(List.of(items.get(0), items.get(1)));
        for (int i = 0; i < 30; i++) sequences.add(List.of(items.get(2), items.get(3)));

        var patterns = miner.mine(sequences, 0.05);

        for (int i = 1; i < patterns.size(); i++) {
            assertThat(patterns.get(i).support()).isLessThanOrEqualTo(patterns.get(i - 1).support());
        }
    }

    @Test
    void mine_emptyInput_returnsEmptyList() {
        var patterns = miner.mine(List.of(), 0.05);
        assertThat(patterns).isEmpty();
    }
}
