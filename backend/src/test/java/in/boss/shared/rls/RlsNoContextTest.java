package in.boss.shared.rls;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that querying tenant-scoped tables without a BossContext returns zero rows
 * rather than throwing an error (issue #13).
 *
 * RLS policies use: app.current_tenant_id = '' (empty string) when no context is set,
 * which matches no tenant UUID → SELECT returns 0 rows, never an exception.
 *
 * Uses a non-superuser connection so RLS policies are actually enforced.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RlsNoContextTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;

    private static final String APP_USER = "boss_app_user";
    private static final String APP_PASS = "boss_app_pass";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("boss_test")
            .withUsername("boss")
            .withPassword("boss");

    static JdbcTemplate appJdbc;

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void setupAppUser(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN "
                + "CREATE USER " + APP_USER + " WITH PASSWORD '" + APP_PASS + "'; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
        jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + APP_USER);
        jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + APP_USER);

        // Seed a tenant row via superuser so there is data for the isolation check
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('No-Context Group','no-ctx-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));
        UUID tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'No-Context Tenant', 'no-ctx-tenant', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));
        // Insert an item so the items table has data
        jdbc.update(
                "INSERT INTO items (tenant_id, name, sku, uom_id) "
                + "SELECT ?, 'Test Item', 'SKU-NO-CTX', id FROM uoms WHERE tenant_id = ? LIMIT 1",
                tenantId, tenantId);

        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), APP_USER, APP_PASS);
        appJdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void clearContext() {
        BossContextHolder.clear();
    }

    @Test
    void noContext_items_returnsZeroRows() {
        BossContextHolder.clear();
        // Apply empty tenant context via raw SQL (simulates BossConnectionWrapper with no context)
        appJdbc.execute("SET LOCAL app.current_tenant_id = ''");

        int count = appJdbc.queryForObject("SELECT COUNT(*) FROM items", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void noContext_auditEvents_returnsZeroRows() {
        BossContextHolder.clear();
        appJdbc.execute("SET LOCAL app.current_tenant_id = ''");

        int count = appJdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void noContext_doesNotThrow() {
        BossContextHolder.clear();
        // The critical contract: absent context must return 0 rows, never raise an exception
        appJdbc.execute("SET LOCAL app.current_tenant_id = ''");
        assertThat(appJdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class)).isZero();
        assertThat(appJdbc.queryForObject("SELECT COUNT(*) FROM approval_requests", Integer.class)).isZero();
        assertThat(appJdbc.queryForObject("SELECT COUNT(*) FROM stock_movements", Integer.class)).isZero();
    }
}
