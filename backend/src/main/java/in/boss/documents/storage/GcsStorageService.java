package in.boss.documents.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class GcsStorageService implements StorageService {
    private final Storage storage;
    private final String bucketName;

    public GcsStorageService(Storage storage, String bucketName) {
        this.storage = storage;
        this.bucketName = bucketName;
    }

    @Override
    public String upload(String objectKey, InputStream content, String contentType, long size) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(contentType != null ? contentType : "application/octet-stream")
                    .build();
            byte[] bytes = content.readAllBytes();
            storage.create(blobInfo, bytes);
            return "gs://" + bucketName + "/" + objectKey;
        } catch (IOException | StorageException e) {
            throw new RuntimeException("Failed to upload object to GCS: " + objectKey, e);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectKey);
            Blob blob = storage.get(blobId);
            if (blob == null || !blob.exists()) {
                throw new RuntimeException("Object not found in GCS: " + objectKey);
            }
            return blob.getContent();
        } catch (StorageException e) {
            throw new RuntimeException("Failed to download object from GCS: " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectKey);
            Blob blob = storage.get(blobId);
            return blob != null && blob.exists();
        } catch (StorageException e) {
            return false;
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectKey);
            storage.delete(blobId);
        } catch (StorageException e) {
            log.warn("Storage deletion error for key: {}", objectKey, e);
        }
    }
}
