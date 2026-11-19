package in.boss.documents.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStorageService implements StorageService {
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public String upload(String objectKey, InputStream content, String contentType, long size) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            content.transferTo(baos);
            byte[] bytes = baos.toByteArray();
            store.put(objectKey, bytes);
            return "mem://" + objectKey;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file in memory", e);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        byte[] bytes = store.get(objectKey);
        if (bytes == null) {
            throw new RuntimeException("Object not found in memory store: " + objectKey);
        }
        return bytes;
    }

    @Override
    public boolean exists(String objectKey) {
        return store.containsKey(objectKey);
    }

    @Override
    public void delete(String objectKey) {
        store.remove(objectKey);
    }
}
