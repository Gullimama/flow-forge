package com.flowforge.common.service;

import com.flowforge.common.TestJpaApplication;
import com.flowforge.common.entity.SnapshotStatus;
import com.flowforge.common.model.SnapshotMetadata;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class MetadataServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge")
        .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MetadataService metadataService;

    @Test
    void fullSnapshotLifecycle() {
        SnapshotMetadata meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha-1", SnapshotMetadata.SnapshotType.BASELINE, Instant.now(), List.of()
        );
        UUID snapshotId = metadataService.createSnapshot(meta);
        metadataService.updateSnapshotStatus(snapshotId, SnapshotStatus.COMPLETED);

        var latest = metadataService.getLatestSnapshot();
        assertThat(latest).isPresent();
        assertThat(latest.get().getStatus()).isEqualTo(SnapshotStatus.COMPLETED);
    }

    @Test
    void connectionPoolHandlesConcurrentOperations() throws Exception {
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    metadataService.createJob("LOAD_TEST", Map.of());
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        assertThat(errors.get()).isZero();
    }
}
