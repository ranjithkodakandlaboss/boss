package in.boss.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SuspendedTenantInterceptorTest {
    @AfterEach
    void clearRequestContext() {
        BossContextHolder.clear();
    }

    @Test
    void rejectsSuspendedTenantRequestsWithForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, "firebase-user"));
        TenantStatusCache statusCache = new TenantStatusCache(new TenantStatusCacheTest.StubStatusJdbcTemplate("suspended"));
        SuspendedTenantInterceptor interceptor = new SuspendedTenantInterceptor(statusCache);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("TENANT_SUSPENDED");
    }
}
