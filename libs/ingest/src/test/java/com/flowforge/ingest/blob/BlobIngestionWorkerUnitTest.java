package com.flowforge.ingest.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.service.MetadataService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class BlobIngestionWorkerUnitTest {

    @Mock
    private AzureBlobClient azureBlobClient;
    @Mock
    private ZipExtractor zipExtractor;
    @Mock
    private MinioStorageClient minioClient;
    @Mock
    private MetadataService metadataService;

    @InjectMocks
    private BlobIngestionWorker worker;

    @Test
    void executeFull_skipsAlreadyDownloadedBlobs() throws Exception {
        BlobItem blob = mock(BlobItem.class);
        when(blob.getName()).thenReturn("logs/app.zip");
        when(azureBlobClient.listBlobs(any(), any())).thenReturn(List.of(blob));

        BlobProperties props = mock(BlobProperties.class);
        when(props.getETag()).thenReturn("etag-123");
        when(props.getBlobSize()).thenReturn(100L);
        when(props.getLastModified()).thenReturn(OffsetDateTime.now());
        when(azureBlobClient.getBlobProperties(eq("test-container"), eq("logs/app.zip"))).thenReturn(props);

        when(metadataService.existsBlobByEtag("etag-123")).thenReturn(true);

        BlobIngestionConfig config = new BlobIngestionConfig("test-container", "logs/");
        UUID jobId = UUID.randomUUID();
        when(metadataService.createBlobBatch(any())).thenReturn(jobId);

        BatchIngestionResult result = worker.executeFull(jobId, config);

        assertThat(result.skippedBlobs()).isEqualTo(1);
        assertThat(result.downloadedBlobs()).isZero();
        verify(azureBlobClient, never()).downloadBlob(any(), any(), any());
    }
}
