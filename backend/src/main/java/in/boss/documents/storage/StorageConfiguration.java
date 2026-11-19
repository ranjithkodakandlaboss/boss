package in.boss.documents.storage;

import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfiguration {

    @Bean
    @ConditionalOnProperty(name = "boss.documents.storage-type", havingValue = "in-memory")
    public StorageService inMemoryStorageService() {
        return new InMemoryStorageService();
    }

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService storageService(
            @Autowired(required = false) Storage storage,
            @Value("${boss.documents.bucket:boss-dev-documents-201664309080}") String bucketName,
            @Value("${boss.documents.storage-type:gcs}") String storageType) {
        if ("in-memory".equalsIgnoreCase(storageType) || storage == null) {
            return new InMemoryStorageService();
        }
        return new GcsStorageService(storage, bucketName);
    }
}
