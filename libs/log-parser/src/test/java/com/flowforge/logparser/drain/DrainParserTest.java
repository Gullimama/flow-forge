package com.flowforge.logparser.drain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DrainParserTest {

    private DrainParser drainParser;

    @BeforeEach
    void setUp() {
        drainParser = new DrainParser(0.5, 4, 100);
    }

    @Test
    void parse_similarMessages_groupsIntoSingleCluster() {
        drainParser.parse("Connected to 10.0.1.5 on port 5432");
        drainParser.parse("Connected to 10.0.1.6 on port 5432");
        drainParser.parse("Connected to 192.168.1.1 on port 3306");

        assertThat(drainParser.getClusters()).hasSize(1);
        DrainParser.LogCluster cluster = drainParser.getClusters().values().iterator().next();
        assertThat(cluster.templateString()).contains("<IP>");
        assertThat(cluster.matchCount().get()).isEqualTo(3);
    }

    @Test
    void parse_differentMessages_createsSeparateClusters() {
        drainParser.parse("Connected to 10.0.1.5 on port 5432");
        drainParser.parse("Failed to authenticate user admin");
        drainParser.parse("Request processed in 150ms");

        assertThat(drainParser.getClusters()).hasSize(3);
    }

    @Test
    void parse_uuidMasking_replacesWithPlaceholder() {
        DrainParser.LogCluster cluster = drainParser.parse(
            "Processing request a1b2c3d4-e5f6-7890-abcd-ef1234567890");

        assertThat(cluster.templateString()).contains("<UUID>");
        assertThat(cluster.templateString()).doesNotContain("a1b2c3d4");
    }

    @Test
    void parse_numericMasking_replacesNumbers() {
        DrainParser.LogCluster cluster = drainParser.parse("Response time 250 ms, status 200");

        assertThat(cluster.templateString()).contains("<NUM>");
    }

    @Test
    void parse_pathMasking_replacesFilePaths() {
        DrainParser.LogCluster cluster = drainParser.parse("Loading config from /etc/app/config.yml");

        assertThat(cluster.templateString()).contains("<PATH>");
    }

    @Test
    void parse_threadSafety_noConcurrentModificationException() throws Exception {
        int threadCount = 10;
        int messagesPerThread = 100;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < messagesPerThread; i++) {
                        drainParser.parse("Service-%d processed request %d in %dms"
                            .formatted(threadId, i, (int) (Math.random() * 500)));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(drainParser.getClusters()).isNotEmpty();
        long totalMatches = drainParser.getClusters().values().stream()
            .mapToLong(c -> c.matchCount().get())
            .sum();
        assertThat(totalMatches).isEqualTo(threadCount * messagesPerThread);
    }

    @Test
    void parse_doubleCheckLocking_producesConsistentResults() throws Exception {
        var latch = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        int concurrentParsers = 50;
        List<Future<DrainParser.LogCluster>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentParsers; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return drainParser.parse("Connection reset by peer 10.0.0.1");
            }));
        }
        latch.countDown();

        List<String> clusterIds = futures.stream()
            .map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .map(DrainParser.LogCluster::clusterId)
            .distinct()
            .toList();

        assertThat(clusterIds).hasSize(1);
    }
}
