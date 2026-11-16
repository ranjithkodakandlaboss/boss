package in.boss.shared.security;

import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContextHolder;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests that a structurally invalid Firebase JWT (tampered / wrong project) results in 401 (issue #19).
 */
@ExtendWith(MockitoExtension.class)
class InvalidJwtTest {

    @Mock FirebaseAuth firebaseAuth;
    @Mock TenantContextResolver tenantContextResolver;

    @InjectMocks FirebaseJwtFilter filter;

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void malformedToken_returns401() throws Exception {
        when(firebaseAuth.verifyIdToken(anyString()))
                .thenThrow(new IllegalArgumentException("Token was not a valid JWT"));

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not.a.valid.jwt");
        var response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (rq, rs) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chainCalled.get()).isFalse();
    }

    @Test
    void wrongProjectToken_returns401() throws Exception {
        // Token from a different Firebase project fails signature verification
        when(firebaseAuth.verifyIdToken(anyString()))
                .thenThrow(new RuntimeException("Firebase ID token has incorrect audience"));

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer wrong-project-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (rq, rs) -> {});

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void invalidToken_securityContextNotPopulated() throws Exception {
        when(firebaseAuth.verifyIdToken(anyString()))
                .thenThrow(new IllegalArgumentException("Invalid JWT"));

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer garbage");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (rq, rs) -> {});

        assertThat(BossContextHolder.get()).isNull();
        assertThat(org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication()).isNull();
    }
}
