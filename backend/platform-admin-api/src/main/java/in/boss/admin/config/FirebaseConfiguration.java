package in.boss.admin.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.admin.security.FirebaseIdTokenVerification;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class FirebaseConfiguration {
    @Bean
    FirebaseApp adminFirebaseApp(@Value("${boss.admin-firebase.project-id}") String projectId) throws IOException {
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(projectId)
                .build();
        return FirebaseApp.initializeApp(options, "boss-platform-admin");
    }

    @Bean
    FirebaseAuth adminFirebaseAuth(FirebaseApp adminFirebaseApp) {
        return FirebaseAuth.getInstance(adminFirebaseApp);
    }

    @Bean
    FirebaseIdTokenVerification firebaseIdTokenVerification(FirebaseAuth adminFirebaseAuth) {
        return token -> adminFirebaseAuth.verifyIdToken(token, true);
    }
}
