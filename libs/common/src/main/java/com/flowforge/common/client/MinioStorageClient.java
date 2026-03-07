package com.flowforge.common.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
public class MinioStorageClient {

    public static final List<String> REQUIRED_BUCKETS = List.of(
        "raw-git", "raw-logs", "parsed-code", "parsed-logs",
        "graph-artifacts", "research-output", "model-artifacts", "evidence"
    );

    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;

    public MinioStorageClient(MinioClient minioClient, ObjectMapper objectMapper) {
        this.minioClient = minioClient;
        this.objectMapper = objectMapper;
    }

    /** Create all required buckets if they don't exist. */
    public void ensureBuckets() {
        for (String bucket : REQUIRED_BUCKETS) {
            try {
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
            } catch (Exception e) {
                throw new StorageException("Failed to ensure bucket: " + bucket, e);
            }
        }
    }

    /** Upload bytes. Returns the full path (bucket/key). */
    public String putObject(String bucket, String key, byte[] data, String contentType) {
        try {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build()
            );
            return bucket + "/" + key;
        } catch (Exception e) {
            throw new StorageException("Failed to put object: " + bucket + "/" + key, e);
        }
    }

    /** Serialize an object to JSON and upload. */
    public String putJson(String bucket, String key, Object obj) {
        try {
            byte[] data = objectMapper.writeValueAsBytes(obj);
            return putObject(bucket, key, data, "application/json");
        } catch (Exception e) {
            throw new StorageException("Failed to put JSON: " + bucket + "/" + key, e);
        }
    }

    /** Download an object as bytes. */
    public byte[] getObject(String bucket, String key) {
        try (var response = minioClient.getObject(
            GetObjectArgs.builder().bucket(bucket).object(key).build()
        )) {
            return response.readAllBytes();
        } catch (Exception e) {
            throw new StorageException("Failed to get object: " + bucket + "/" + key, e);
        }
    }

    /** Download and deserialize a JSON object. */
    public <T> T getJson(String bucket, String key, Class<T> type) {
        byte[] data = getObject(bucket, key);
        try {
            return objectMapper.readValue(data, type);
        } catch (Exception e) {
            throw new StorageException("Failed to deserialize JSON: " + bucket + "/" + key, e);
        }
    }

    /** Download and deserialize a JSON object to a TypeReference. */
    public <T> T getJson(String bucket, String key, TypeReference<T> typeRef) {
        byte[] data = getObject(bucket, key);
        try {
            return objectMapper.readValue(data, typeRef);
        } catch (Exception e) {
            throw new StorageException("Failed to deserialize JSON: " + bucket + "/" + key, e);
        }
    }

    /** Stream download for large objects. Caller must close the stream. */
    public InputStream getObjectStream(String bucket, String key) {
        try {
            return minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build()
            );
        } catch (Exception e) {
            throw new StorageException("Failed to stream object: " + bucket + "/" + key, e);
        }
    }

    /** List objects under a prefix. */
    public List<MinioObjectInfo> listObjects(String bucket, String prefix) {
        var results = new ArrayList<MinioObjectInfo>();
        try {
            var objects = minioClient.listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build()
            );
            for (var result : objects) {
                var item = result.get();
                results.add(new MinioObjectInfo(
                    bucket,
                    item.objectName(),
                    item.size(),
                    item.lastModified() != null ? item.lastModified().toInstant() : null,
                    item.etag()
                ));
            }
        } catch (Exception e) {
            throw new StorageException("Failed to list objects: " + bucket + "/" + prefix, e);
        }
        return results;
    }

    /** Check if an object exists. */
    public boolean objectExists(String bucket, String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new StorageException("Failed to check existence: " + bucket + "/" + key, e);
        } catch (Exception e) {
            throw new StorageException("Failed to check existence: " + bucket + "/" + key, e);
        }
    }

    /** Delete a single object. */
    public void deleteObject(String bucket, String key) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder().bucket(bucket).object(key).build()
            );
        } catch (Exception e) {
            throw new StorageException("Failed to delete object: " + bucket + "/" + key, e);
        }
    }

    /** Copy an object between buckets/keys. */
    public void copyObject(String srcBucket, String srcKey, String dstBucket, String dstKey) {
        try {
            minioClient.copyObject(
                CopyObjectArgs.builder()
                    .source(CopySource.builder().bucket(srcBucket).object(srcKey).build())
                    .bucket(dstBucket)
                    .object(dstKey)
                    .build()
            );
        } catch (Exception e) {
            throw new StorageException("Failed to copy object", e);
        }
    }

    /** Check MinIO connectivity. */
    public boolean healthCheck() {
        try {
            minioClient.listBuckets();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
