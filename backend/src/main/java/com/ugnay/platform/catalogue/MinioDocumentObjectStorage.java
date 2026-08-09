package com.ugnay.platform.catalogue;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
@Profile("!lite")
final class MinioDocumentObjectStorage implements DocumentObjectStorage {
    private final MinioClient minio;
    private final String bucket;

    MinioDocumentObjectStorage(
            @Value("${ugnay.storage.endpoint}") String endpoint,
            @Value("${ugnay.storage.access-key}") String accessKey,
            @Value("${ugnay.storage.secret-key}") String secretKey,
            @Value("${ugnay.storage.bucket}") String bucket) {
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public StoredObject store(String objectKey, Path source, String contentType, Map<String, String> metadata) throws IOException {
        try {
            ensureBucket();
            try (InputStream input = Files.newInputStream(source)) {
                var response = minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                        .contentType(contentType).userMetadata(metadata)
                        .stream(input, Files.size(source), -1).build());
                return new StoredObject(objectKey, response.etag());
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Private document storage is unavailable.", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) throws IOException {
        try {
            return minio.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IOException("Stored document could not be opened for extraction.", exception);
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            throw new IOException("The private document bucket has not been initialized by minio-init.");
        }
    }
}
