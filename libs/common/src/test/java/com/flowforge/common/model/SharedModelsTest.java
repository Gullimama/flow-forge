package com.flowforge.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SharedModelsTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void snapshotMetadataSerializationRoundTrip() throws Exception {
        var meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "abc123", SnapshotMetadata.SnapshotType.BASELINE,
            Instant.now(), List.of("src/Main.java")
        );
        String json = mapper.writeValueAsString(meta);
        var deserialized = mapper.readValue(json, SnapshotMetadata.class);
        assertThat(deserialized).isEqualTo(meta);
    }

    @Test
    void blobIngestionRecordSerializationRoundTrip() throws Exception {
        var record = new BlobIngestionRecord(
            UUID.randomUUID(), "account", "container", "prefix/",
            "app.log.gz", "etag-1", 1024L, Instant.now()
        );
        String json = mapper.writeValueAsString(record);
        var deserialized = mapper.readValue(json, BlobIngestionRecord.class);
        assertThat(deserialized).isEqualTo(record);
    }

    @Test
    void jobStatusSerializationRoundTrip() throws Exception {
        var status = new JobStatus(
            UUID.randomUUID(), "SNAPSHOT", JobStatus.Status.RUNNING,
            Instant.now(), Instant.now(), null, 45.5
        );
        String json = mapper.writeValueAsString(status);
        var deserialized = mapper.readValue(json, JobStatus.class);
        assertThat(deserialized).isEqualTo(status);
    }

    @Test
    void runtimeEventSerializationRoundTrip() throws Exception {
        var event = new RuntimeEvent(
            "evt-1", Instant.now(), RuntimeEvent.SourceType.APP,
            "booking-service", "prod", "pod-1", "trace-1", "span-1",
            "corr-1", "req-1", "GET", "/api/bookings", 200, 12.5,
            "payment-service", null, "OK", Map.of("env", "prod")
        );
        String json = mapper.writeValueAsString(event);
        var deserialized = mapper.readValue(json, RuntimeEvent.class);
        assertThat(deserialized).isEqualTo(event);
    }

    @Test
    void jobStatusEnumValuesAreComplete() {
        assertThat(JobStatus.Status.values()).containsExactly(
            JobStatus.Status.PENDING, JobStatus.Status.RUNNING,
            JobStatus.Status.COMPLETED, JobStatus.Status.FAILED,
            JobStatus.Status.CANCELLED
        );
    }
}
