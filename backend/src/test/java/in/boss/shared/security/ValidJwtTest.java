package in.boss.shared.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that a valid Firebase JWT token is accepted and BossContext is populated (issue #19).
 */
@ExtendWith(MockitoExtension.class)
class ValidJwtTest {

    @Mock FirebaseAuth firebaseAuth;
    @Mock TenantContextResolver tenantContextResolver;

    @InjectMocks FirebaseJwtFilter filter;

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerToken_chainIsCalledAndContextPopulated() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BossContext expectedCtx = new BossContext(
                tenantId, UUID.randomUUID(), Set.of("OWNER"), null, "firebase-uid-valid");

        FirebaseToken mockToken = mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-valid");
        when(firebaseAuth.verifyIdToken("good-token")).thenReturn(mockToken);
        when(tenantContextResolver.resolve("firebase-uid-valid")).thenReturn(expectedCtx);

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");
        var response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (rq, rs) -> {
            chainCalled.set(true);
            // Inside the chain, BossContext must be set
            assertThat(BossContextHolder.get()).isNotNull();
            assertThat(BossContextHolder.get().tenantId()).isEqualTo(tenantId);
        });

        assertThat(chainCalled.get()).isTrue();
        // Context is always cleared after the filter (finally block)
        assertThat(BossContextHolder.get()).isNull();
    }

    @Test
    void validToken_unknownUser_chainCalledWithoutContext() throws Exception {
        // tenantContextResolver returns null when user not found in app_users
        FirebaseToken mockToken = mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-unknown");
        when(firebaseAuth.verifyIdToken("good-token-2")).thenReturn(mockToken);
        when(tenantContextResolver.resolve("firebase-uid-unknown")).thenReturn(null);

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token-2");
        var response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (rq, rs) -> chainCalled.set(true));

        // Chain is still called; authorization enforcement is Spring Security's responsibility
        assertThat(chainCalled.get()).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    void validToken_securityContextContainsRolePrefixedAuthorities() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BossContext ctx = new BossContext(
                tenantId, UUID.randomUUID(), Set.of("OWNER", "OPS_MANAGER"), null, "uid-roles");

        FirebaseToken mockToken = mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("uid-roles");
        when(firebaseAuth.verifyIdToken("role-token")).thenReturn(mockToken);
        when(tenantContextResolver.resolve("uid-roles")).thenReturn(ctx);

        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer role-token");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (rq, rs) -> {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            assertThat(auth).isNotNull();
            var authorities = auth.getAuthorities().stream()
                    .map(a -> a.getAuthority()).toList();
            assertThat(authorities).containsExactlyInAnyOrder("ROLE_OWNER", "ROLE_OPS_MANAGER");
        });
    }
}
