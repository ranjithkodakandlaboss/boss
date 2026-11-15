package in.boss.shared.rls;

import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RlsTenantIsolationTest {

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

    @AfterEach
    void clearContext() {
        BossContextHolder.clear();
    }

    @Test
    void tenantA_cannotReadTenantB_legalEntities() {
        UUID tenantA = provisionTenant("alpha");
        UUID tenantB = provisionTenant("beta");
        UUID entityA = insertLegalEntity(tenantA, "Alpha Corp");
        UUID entityB = insertLegalEntity(tenantB, "Beta Corp");

        // Query as tenant A
        BossContextHolder.set(new BossContext(tenantA, null, Set.of(), null, null));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM legal_entities");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id").toString()).isEqualTo(entityA.toString());
        assertThat(rows).noneMatch(r -> r.get("id").toString().equals(entityB.toString()));
    }

    @Test
    void noContext_returns_zeroRows() {
        UUID tenantA = provisionTenant("gamma");
        insertLegalEntity(tenantA, "Gamma Ltd");

        // Clear context — RLS should return zero rows, not error
        BossContextHolder.clear();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM legal_entities");

        assertThat(rows).isEmpty();
    }

    @Test
    void appendOnly_stockMovements_blocksUpdate() {
        // Verified by DB trigger — integration test: insert succeeds, update fails
        UUID tenantA = provisionTenant("delta");
        // Stock movements table tested separately after V016 migration
        // This test asserts the trigger exists on audit_events
        UUID entityId = UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantA, null, Set.of(), null, null));

        UUID auditId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO audit_events (tenant_id, entity_type, entity_id, action) " +
                "VALUES (?, 'TEST', ?, 'CREATE') RETURNING id",
                String.class,
                tenantA, entityId));

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbc.update("UPDATE audit_events SET action = 'TAMPERED' WHERE id = ?", auditId),
                "Append-only trigger must block UPDATE on audit_events");
    }

    // ---- helpers ----

    private UUID provisionTenant(String slug) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES (?, ?) RETURNING id::text",
                String.class, slug + " Group", slug + "-grp"));
        return UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) " +
                "VALUES (?, ?, ?, 'active') RETURNING id::text",
                String.class, groupId, slug + " Tenant", slug));
    }

    private UUID insertLegalEntity(UUID tenantId, String name) {
        // Bypass RLS for setup: use platform connection (no tenant context)
        BossContextHolder.clear();
        return UUID.fromString(jdbc.queryForObject(
                "INSERT INTO legal_entities (tenant_id, name, legal_name, status) " +
                "VALUES (?, ?, ?, 'active') RETURNING id::text",
                String.class, tenantId, name, name + " Legal"));
    }
}
