package in.boss.shared.security;

import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContextHolder;
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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests that a request with no Authorization header passes through the filter
 * without any Firebase interaction — authentication is deferred to Spring Security (issue #19).
 */
@ExtendWith(MockitoExtension.class)
class MissingJwtTest {

    @Mock FirebaseAuth firebaseAuth;
    @Mock TenantContextResolver tenantContextResolver;

    @InjectMocks FirebaseJwtFilter filter;

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeader_filterPassesThrough() throws Exception {
        var request = new MockHttpServletRequest();
        // No Authorization header
        var response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (rq, rs) -> chainCalled.set(true));

        // Filter calls chain — it does not return 401 (that is Spring Security's job)
        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void noAuthorizationHeader_firebaseNotCalled() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (rq, rs) -> {});

        verifyNoInteractions(firebaseAuth);
        verifyNoInteractions(tenantContextResolver);
    }

    @Test
    void noAuthorizationHeader_securityContextEmpty() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (rq, rs) -> {});

        assertThat(BossContextHolder.get()).isNull();
        assertThat(org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerAuthorizationHeader_treatedAsMissing() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz"); // Basic auth — not supported
        var response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (rq, rs) -> chainCalled.set(true));

        // Non-Bearer tokens are ignored; filter passes through
        assertThat(chainCalled.get()).isTrue();
        verifyNoInteractions(firebaseAuth);
    }
}
