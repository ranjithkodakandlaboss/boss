package in.boss.shared.rls;

import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

/**
 * Tests that RLS policies actually isolate tenant data.
 *
 * IMPORTANT: RLS is bypassed by PostgreSQL superusers regardless of set_config.
 * The Testcontainers 'boss' user is a superuser (POSTGRES_USER → superuser).
 * All RLS assertions therefore run through a secondary connection as 'boss_app_user'
 * which is NOT a superuser, so RLS policies are properly enforced.
 *
 * Setup inserts use the superuser JdbcTemplate (bypassing RLS intentionally,
 * to populate data across multiple tenants for the isolation test).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RlsTenantIsolationTest {

    private static final String APP_USER = "boss_app_user";
    private static final String APP_PASS = "boss_app_pass";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("boss_test")
            .withUsername("boss")
            .withPassword("boss");

    /** Non-superuser JdbcTemplate used for all RLS assertions. */
    static JdbcTemplate appJdbc;

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * Creates a non-superuser login role (boss_app_user) and grants it the
     * boss_app role (created by V061). This user is what the production app
     * connection pool would use, so RLS is enforced.
     */
    @BeforeAll
    static void setupAppUser(@Autowired JdbcTemplate superJdbc) {
        // Create login user; idempotent if already exists
        superJdbc.execute("DO $$ BEGIN " +
                "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN " +
                "  CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASS + "' " +
                "    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION; " +
                "END IF; " +
                "END $$");

        // Grant boss_app role (created by V061 migration)
        try {
            superJdbc.execute("GRANT boss_app TO " + APP_USER);
        } catch (Exception ignored) {
            // boss_app role may not exist in test environment; grant table-level directly
            superJdbc.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
            superJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE " +
                    "ON ALL TABLES IN SCHEMA public TO " + APP_USER);
        }

        // Build non-superuser JdbcTemplate
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUsername(APP_USER);
        ds.setPassword(APP_PASS);
        appJdbc = new JdbcTemplate(ds);
    }

    @Autowired
    JdbcTemplate jdbc;  // superuser — used only for test data setup

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

        // Assert using non-superuser connection with tenant A context
        List<Map<String, Object>> rows = queryAsNonSuperuser(tenantA,
                "SELECT id FROM legal_entities");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id").toString()).isEqualTo(entityA.toString());
        assertThat(rows).noneMatch(r -> r.get("id").toString().equals(entityB.toString()));
    }

    @Test
    void noContext_returns_zeroRows() {
        UUID tenantA = provisionTenant("gamma");
        insertLegalEntity(tenantA, "Gamma Ltd");

        // No tenant context set — boss_current_tenant_id() returns NULL → zero rows
        List<Map<String, Object>> rows = queryAsNonSuperuser(null,
                "SELECT id FROM legal_entities");

        assertThat(rows).isEmpty();
    }

    @Test
    void appendOnly_stockMovements_blocksUpdate() {
        UUID tenantA = provisionTenant("delta");
        BossContextHolder.set(new BossContext(tenantA, null, Set.of(), null, null));

        UUID entityId = UUID.randomUUID();
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

    /**
     * Runs a query as the non-superuser 'boss_app_user' with the given tenant context.
     * Uses SET LOCAL so the context is scoped to the transaction.
     *
     * @param tenantId null means no tenant context (should return zero rows due to RLS)
     */
    private List<Map<String, Object>> queryAsNonSuperuser(UUID tenantId, String sql) {
        return appJdbc.execute((java.sql.Connection conn) -> {
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement()) {
                String tenantValue = tenantId != null ? tenantId.toString() : "";
                stmt.execute("SELECT set_config('app.current_tenant_id', '" +
                        tenantValue + "', true)");
                try (var rs = stmt.executeQuery(sql)) {
                    var rows = new java.util.ArrayList<Map<String, Object>>();
                    var meta = rs.getMetaData();
                    while (rs.next()) {
                        var row = new java.util.LinkedHashMap<String, Object>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnName(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        });
    }

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
        // Superuser can bypass RLS to insert data for multiple tenants
        BossContextHolder.clear();
        return UUID.fromString(jdbc.queryForObject(
                "INSERT INTO legal_entities (tenant_id, name, legal_name, status) " +
                "VALUES (?, ?, ?, 'active') RETURNING id::text",
                String.class, tenantId, name, name + " Legal"));
    }
}
