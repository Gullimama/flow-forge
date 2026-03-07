package com.flowforge.common.health;

import com.flowforge.common.client.MinioStorageClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioStorageClient storage;

    public MinioHealthIndicator(MinioStorageClient storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        return storage.healthCheck()
            ? Health.up().withDetail("buckets", MinioStorageClient.REQUIRED_BUCKETS.size()).build()
            : Health.down().withDetail("error", "MinIO unreachable").build();
    }
}
