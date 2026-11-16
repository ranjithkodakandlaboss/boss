package in.boss.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class FirebaseAdminTokenVerifier implements AdminTokenVerifier {
    private final FirebaseIdTokenVerification firebaseVerification;
    private final String adminProjectId;
    private final ObjectMapper objectMapper;

    public FirebaseAdminTokenVerifier(FirebaseIdTokenVerification firebaseVerification,
                                      @Value("${boss.admin-firebase.project-id}") String adminProjectId,
                                      ObjectMapper objectMapper) {
        this.firebaseVerification = firebaseVerification;
        this.adminProjectId = adminProjectId;
        this.objectMapper = objectMapper;
    }

    @Override
    public VerifiedAdminToken verify(String token) {
        Map<?, ?> claims = untrustedClaims(token);
        Object audience = claims.get("aud");
        Object issuer = claims.get("iss");
        if (!adminProjectId.equals(audience)
                || !("https://securetoken.google.com/" + adminProjectId).equals(issuer)) {
            // Claims are used only to reject a token early. Authorization always requires
            // Firebase signature verification below for tokens claiming the admin project.
            throw new CustomerProjectTokenException();
        }
        try {
            var verified = firebaseVerification.verify(token);
            if (!adminProjectId.equals(verified.getClaims().get("aud"))) {
                throw new CustomerProjectTokenException();
            }
            return new VerifiedAdminToken(verified.getUid(), verified.getEmail());
        } catch (CustomerProjectTokenException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadCredentialsException("Invalid platform administrator token", exception);
        }
    }

    private Map<?, ?> untrustedClaims(String token) {
        try {
            String[] sections = token.split("\\.");
            if (sections.length != 3) throw new IllegalArgumentException("Malformed JWT");
            byte[] payload = Base64.getUrlDecoder().decode(sections[1]);
            return objectMapper.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
        } catch (Exception exception) {
            throw new BadCredentialsException("Malformed bearer token", exception);
        }
    }

    public static final class CustomerProjectTokenException extends InsufficientAuthenticationException {
        public CustomerProjectTokenException() {
            super("Customer Firebase tokens cannot authenticate to the platform admin API");
        }
    }
}
