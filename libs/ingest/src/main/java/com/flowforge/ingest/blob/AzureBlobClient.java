package com.flowforge.ingest.blob;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.ListBlobsOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Lists, downloads, and reads properties of blobs from Azure Blob Storage (or Azurite).
 */
@Component
@ConditionalOnBean(com.azure.storage.blob.BlobServiceClient.class)
public class AzureBlobClient {

    private final BlobServiceClient blobServiceClient;

    public AzureBlobClient(BlobServiceClient blobServiceClient) {
        this.blobServiceClient = blobServiceClient;
    }

    /**
     * List all blobs matching prefix.
     */
    public List<BlobItem> listBlobs(String container, String prefix) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(container);
        List<BlobItem> items = new ArrayList<>();
        ListBlobsOptions options = new ListBlobsOptions();
        if (prefix != null && !prefix.isEmpty()) {
            options.setPrefix(prefix);
        }
        containerClient.listBlobs(options, null).forEach(items::add);
        return items;
    }

    /**
     * Download a blob to a local temp file in the target directory.
     */
    public Path downloadBlob(String container, String blobName, Path targetDir) throws IOException {
        BlobClient blobClient = blobServiceClient.getBlobContainerClient(container).getBlobClient(blobName);
        Path localFile = targetDir.resolve(Path.of(blobName).getFileName().toString());
        if (localFile.getParent() != null && !Files.exists(localFile.getParent())) {
            Files.createDirectories(localFile.getParent());
        }
        blobClient.downloadToFile(localFile.toString());
        return localFile;
    }

    /**
     * Get blob properties (etag, size, last modified).
     */
    public BlobProperties getBlobProperties(String container, String blobName) {
        BlobClient blobClient = blobServiceClient.getBlobContainerClient(container).getBlobClient(blobName);
        return blobClient.getProperties();
    }
}
