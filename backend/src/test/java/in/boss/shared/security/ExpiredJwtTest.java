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
 * Tests that an expired Firebase JWT token results in 401 Unauthorized (issue #19).
 */
@ExtendWith(MockitoExtension.class)
class ExpiredJwtTest {

    @Mock FirebaseAuth firebaseAuth;
    @Mock TenantContextResolver tenantContextResolver;

    @InjectMocks FirebaseJwtFilter filter;

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void expiredToken_returns401_andChainNotCalled() throws Exception {
        // Firebase throws when the token is expired
        when(firebaseAuth.verifyIdToken(anyString()))
                .thenThrow(new RuntimeException("Token expired"));

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer expired.jwt.token");
        var response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (rq, rs) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chainCalled.get())
                .as("Chain must NOT be called on expired token")
                .isFalse();
    }

    @Test
    void expiredToken_contextNotLeaked() throws Exception {
        when(firebaseAuth.verifyIdToken(anyString()))
                .thenThrow(new RuntimeException("Token expired"));

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer stale-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (rq, rs) -> {});

        // finally block must have cleared context regardless of exception
        assertThat(BossContextHolder.get()).isNull();
        assertThat(org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication()).isNull();
    }
}
