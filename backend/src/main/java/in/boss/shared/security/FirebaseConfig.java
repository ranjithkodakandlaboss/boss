package in.boss.shared.security;

import com.google.api.client.http.apache.v2.ApacheHttpTransport;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${boss.firebase.project-id}")
    private String projectId;

    @Value("${boss.firebase.credential-path:}")
    private String credentialPath;

    @Bean
    @ConditionalOnMissingBean(FirebaseApp.class)
    public FirebaseApp firebaseApp() throws IOException, GeneralSecurityException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        FirebaseOptions.Builder builder = FirebaseOptions.builder()
                .setProjectId(projectId)
                .setHttpTransport(new ApacheHttpTransport());

        if (StringUtils.hasText(credentialPath)) {
            try (var is = Files.newInputStream(Path.of(credentialPath))) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(is);
                builder.setCredentials(credentials);
            }
        } else {
            try {
                builder.setCredentials(GoogleCredentials.getApplicationDefault());
            } catch (IOException e) {
                log.warn("No Firebase credentials available — JWT validation will reject all tokens");
                builder.setCredentials(GoogleCredentials.newBuilder().build());
            }
        }

        return FirebaseApp.initializeApp(builder.build());
    }

    @Bean
    @ConditionalOnMissingBean(FirebaseAuth.class)
    public FirebaseAuth firebaseAuth(FirebaseApp app) {
        return FirebaseAuth.getInstance(app);
    }
}
