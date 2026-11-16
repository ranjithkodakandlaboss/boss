package in.boss.shared.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BossContext record and BossContextHolder ThreadLocal (issue #16).
 *
 * Verifies:
 * - set/get/clear lifecycle
 * - hasRole / hasAnyRole logic
 * - ThreadLocal isolation between threads
 */
class BossContextPopulationTest {

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
    }

    @Test
    void set_and_get_returnsSameContext() {
        UUID tenantId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        BossContext ctx = new BossContext(tenantId, userId, Set.of("OWNER"), null, "uid1");

        BossContextHolder.set(ctx);

        assertThat(BossContextHolder.get()).isSameAs(ctx);
        assertThat(BossContextHolder.isSet()).isTrue();
    }

    @Test
    void clear_removesContext() {
        BossContextHolder.set(new BossContext(UUID.randomUUID(), null, Set.of(), null, null));
        BossContextHolder.clear();

        assertThat(BossContextHolder.get()).isNull();
        assertThat(BossContextHolder.isSet()).isFalse();
    }

    @Test
    void get_withoutSet_returnsNull() {
        assertThat(BossContextHolder.get()).isNull();
    }

    @Test
    void hasRole_matchesExactRole() {
        BossContext ctx = new BossContext(null, null, Set.of("OWNER", "OPS_MANAGER"), null, null);

        assertThat(ctx.hasRole("OWNER")).isTrue();
        assertThat(ctx.hasRole("OPS_MANAGER")).isTrue();
        assertThat(ctx.hasRole("SALES_MANAGER")).isFalse();
    }

    @Test
    void hasAnyRole_trueWhenAtLeastOneMatches() {
        BossContext ctx = new BossContext(null, null, Set.of("STORE_KEEPER"), null, null);

        assertThat(ctx.hasAnyRole("OWNER", "STORE_KEEPER")).isTrue();
        assertThat(ctx.hasAnyRole("OWNER", "OPS_MANAGER")).isFalse();
    }

    @Test
    void hasRole_withNullRoles_returnsFalse() {
        BossContext ctx = new BossContext(null, null, null, null, null);
        assertThat(ctx.hasRole("OWNER")).isFalse();
    }

    @Test
    void threadLocal_isolationBetweenThreads() throws InterruptedException {
        UUID mainTenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        AtomicReference<BossContext> otherThreadCtx = new AtomicReference<>();

        BossContextHolder.set(new BossContext(mainTenant, null, Set.of("OWNER"), null, null));

        Thread other = new Thread(() -> {
            // Other thread must NOT see the main thread's context
            otherThreadCtx.set(BossContextHolder.get());
            // Then set its own context
            BossContextHolder.set(new BossContext(otherTenant, null, Set.of("SALES_MANAGER"), null, null));
        });
        other.start();
        other.join();

        // Other thread started with no context (ThreadLocal isolation)
        assertThat(otherThreadCtx.get()).isNull();
        // Main thread context unchanged
        assertThat(BossContextHolder.get().tenantId()).isEqualTo(mainTenant);
    }

    @Test
    void bossContext_allFieldsAccessible() {
        UUID tenantId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        UUID siteId   = UUID.randomUUID();

        BossContext ctx = new BossContext(tenantId, userId, Set.of("OWNER"), siteId, "fb-uid");

        assertThat(ctx.tenantId()).isEqualTo(tenantId);
        assertThat(ctx.userId()).isEqualTo(userId);
        assertThat(ctx.siteId()).isEqualTo(siteId);
        assertThat(ctx.firebaseUid()).isEqualTo("fb-uid");
        assertThat(ctx.roles()).containsExactly("OWNER");
    }
}
