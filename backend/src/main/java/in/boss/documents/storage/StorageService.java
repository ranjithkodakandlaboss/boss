package in.boss.documents.storage;

import java.io.InputStream;

public interface StorageService {
    String upload(String objectKey, InputStream content, String contentType, long size);
    byte[] download(String objectKey);
    boolean exists(String objectKey);
    void delete(String objectKey);
}
