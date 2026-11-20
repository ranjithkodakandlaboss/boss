package in.boss.shared.security;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies tenant membership enforcement at the data layer.
 *
 * A Firebase UID maps to exactly one tenant (enforced by V125 unique index).
 * Verifies:
 * - boss_resolve_firebase_uid returns at most one tenant per UID.
 * - A UID not in app_users returns empty.
 * - RLS prevents a tenant's connection context from reading another tenant's data.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CrossTenantAccessTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("boss_test")
            .withUsername("boss")
            .withPassword("boss");

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TenantContextResolver tenantContextResolver;

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
    }

    @Test
    void unknownFirebaseUid_resolvedContextIsNull() {
        BossContext ctx = tenantContextResolver.resolve("firebase-uid-not-in-db-" + UUID.randomUUID());
        assertThat(ctx).isNull();
    }

    @Test
    void unknownFirebaseUid_isNotInactive() {
        boolean inactive = tenantContextResolver.isInactive("firebase-uid-not-in-db-" + UUID.randomUUID());
        assertThat(inactive).isFalse();
    }

    @Test
    void bossResolveFunction_returnsAtMostOneTenantPerUid() {
        // Seed two tenants and two users with DIFFERENT firebase UIDs to validate the function
        // returns exactly one row per distinct UID.
        UUID groupId = insertCustomerGroup();
        UUID tenantA = insertTenant(groupId, "tenant-a-" + UUID.randomUUID());
        UUID tenantB = insertTenant(groupId, "tenant-b-" + UUID.randomUUID());

        String uidA = "test-uid-a-" + UUID.randomUUID();
        String uidB = "test-uid-b-" + UUID.randomUUID();

        UUID userA = insertUser(tenantA, uidA, "usera@example.test");
        UUID userB = insertUser(tenantB, uidB, "userb@example.test");
        insertRole(tenantA, userA, "OWNER");
        insertRole(tenantB, userB, "OWNER");

        List<Map<String, Object>> rowsA = jdbc.queryForList(
                "SELECT out_tenant_id FROM boss_resolve_firebase_uid(?)", uidA);
        List<Map<String, Object>> rowsB = jdbc.queryForList(
                "SELECT out_tenant_id FROM boss_resolve_firebase_uid(?)", uidB);

        // Each UID resolves to exactly its own tenant
        assertThat(rowsA).hasSize(1);
        assertThat(rowsA.get(0).get("out_tenant_id").toString()).isEqualTo(tenantA.toString());

        assertThat(rowsB).hasSize(1);
        assertThat(rowsB.get(0).get("out_tenant_id").toString()).isEqualTo(tenantB.toString());
    }

    @Test
    void rlsIsolation_tenantAContextCannotReadTenantBData() {
        // Seed two tenants, insert a row in each, then verify that setting tenant A's context
        // hides tenant B's rows and vice versa.
        UUID groupId = insertCustomerGroup();
        UUID tenantA = insertTenant(groupId, "rls-a-" + UUID.randomUUID());
        UUID tenantB = insertTenant(groupId, "rls-b-" + UUID.randomUUID());

        // Insert a physical_site for each tenant (physical_sites has RLS)
        UUID siteA = insertSite(tenantA, "Site A");
        UUID siteB = insertSite(tenantB, "Site B");

        // With tenant A context: can see siteA, cannot see siteB
        jdbc.execute("SELECT set_config('app.current_tenant_id', '" + tenantA + "', false)");
        List<Map<String, Object>> seenByA = jdbc.queryForList(
                "SELECT id FROM physical_sites WHERE id IN ('" + siteA + "', '" + siteB + "')");
        assertThat(seenByA).hasSize(1);
        assertThat(seenByA.get(0).get("id").toString()).isEqualTo(siteA.toString());

        // With tenant B context: can see siteB, cannot see siteA
        jdbc.execute("SELECT set_config('app.current_tenant_id', '" + tenantB + "', false)");
        List<Map<String, Object>> seenByB = jdbc.queryForList(
                "SELECT id FROM physical_sites WHERE id IN ('" + siteA + "', '" + siteB + "')");
        assertThat(seenByB).hasSize(1);
        assertThat(seenByB.get(0).get("id").toString()).isEqualTo(siteB.toString());

        // With empty context: can see nothing (secure default)
        jdbc.execute("SELECT set_config('app.current_tenant_id', '', false)");
        List<Map<String, Object>> seenByNobody = jdbc.queryForList(
                "SELECT id FROM physical_sites WHERE id IN ('" + siteA + "', '" + siteB + "')");
        assertThat(seenByNobody).isEmpty();
    }

    // --- helpers ---

    private UUID insertCustomerGroup() {
        return jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Test Group', 'test-group-" + UUID.randomUUID() + "') RETURNING id",
                UUID.class);
    }

    private UUID insertTenant(UUID groupId, String slug) {
        return jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) " +
                "VALUES (?, ?, ?, 'active') RETURNING id",
                UUID.class, groupId, slug, slug);
    }

    private UUID insertUser(UUID tenantId, String firebaseUid, String email) {
        return jdbc.queryForObject(
                "INSERT INTO app_users (tenant_id, firebase_uid, email, status) " +
                "VALUES (?, ?, ?, 'active') RETURNING id",
                UUID.class, tenantId, firebaseUid, email);
    }

    private void insertRole(UUID tenantId, UUID userId, String role) {
        jdbc.update(
                "INSERT INTO user_roles (tenant_id, user_id, role) VALUES (?, ?, ?)",
                tenantId, userId, role);
    }

    private UUID insertSite(UUID tenantId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO physical_sites (tenant_id, name, code, site_type) " +
                "VALUES (?, ?, ?, 'FACTORY') RETURNING id",
                UUID.class, tenantId, name, "SITE-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    }
}
