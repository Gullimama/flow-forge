package com.flowforge.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.model.SnapshotMetadata;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MinioStorageClientUnitTest {

    @Mock private MinioClient minioClient;
    @Mock private ObjectMapper objectMapper;

    private MinioStorageClient storageClient;

    @BeforeEach
    void setUp() {
        storageClient = new MinioStorageClient(minioClient, objectMapper);
    }

    @Test
    void putJsonSerializesObjectBeforeUpload() throws Exception {
        var record = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha1", SnapshotMetadata.SnapshotType.BASELINE,
            Instant.now(), List.of()
        );
        byte[] expectedBytes = "{\"snapshotId\":\"test\"}".getBytes();
        when(objectMapper.writeValueAsBytes(record)).thenReturn(expectedBytes);

        storageClient.putJson("parsed-code", "snap/meta.json", record);

        verify(objectMapper).writeValueAsBytes(record);
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void getJsonDeserializesToExpectedType() throws Exception {
        byte[] data = "{\"bucket\":\"raw-git\",\"key\":\"k\"}".getBytes();

        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(mockResponse.readAllBytes()).thenReturn(data);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        var expected = new MinioObjectInfo("raw-git", "k", 100L, Instant.now(), "etag");
        when(objectMapper.readValue(any(byte[].class), eq(MinioObjectInfo.class)))
            .thenReturn(expected);

        var result = storageClient.getJson("raw-git", "k", MinioObjectInfo.class);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void objectExistsReturnsFalseOnNoSuchKeyException() throws Exception {
        ErrorResponse er = new ErrorResponse("NoSuchKey", "Not found", "", "", "", "", "");
        ErrorResponseException ex = new ErrorResponseException(er, null, "");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(ex);

        assertThat(storageClient.objectExists("raw-git", "missing.txt")).isFalse();
    }

    @Test
    void healthCheckReturnsFalseOnException() throws Exception {
        when(minioClient.listBuckets()).thenThrow(new RuntimeException("connection refused"));

        assertThat(storageClient.healthCheck()).isFalse();
    }
}
