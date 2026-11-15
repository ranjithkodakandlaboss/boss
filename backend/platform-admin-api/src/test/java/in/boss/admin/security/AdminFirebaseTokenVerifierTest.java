package in.boss.admin.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

class AdminFirebaseTokenVerifierTest {
    private final FirebaseAdminTokenVerifier verifier =
            new FirebaseAdminTokenVerifier(token -> { throw new AssertionError("must not verify customer token"); },
                    "boss-admin-test", new ObjectMapper());

    @Test
    void customerProjectTokenIsRejectedBeforeAdminFirebaseVerification() {
        String token = token("customer-project", "https://securetoken.google.com/customer-project");
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(FirebaseAdminTokenVerifier.CustomerProjectTokenException.class);
    }

    @Test
    void adminProjectClaimsStillRequireFirebaseSignatureVerification() {
        String token = token("boss-admin-test", "https://securetoken.google.com/boss-admin-test");
        FirebaseAdminTokenVerifier adminVerifier = new FirebaseAdminTokenVerifier(ignoredToken -> null,
                "boss-admin-test", new ObjectMapper());
        assertThatThrownBy(() -> adminVerifier.verify(token)).isInstanceOf(BadCredentialsException.class);
    }

    private String token(String audience, String issuer) {
        try {
            String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    new ObjectMapper().writeValueAsBytes(Map.of("aud", audience, "iss", issuer, "sub", "uid-1")));
            return header + "." + payload + ".signature";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
