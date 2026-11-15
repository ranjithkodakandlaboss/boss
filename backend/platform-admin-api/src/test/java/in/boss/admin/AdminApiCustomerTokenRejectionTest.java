package in.boss.admin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.boss.admin.security.AdminTokenVerifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminApiCustomerTokenRejectionTest {
    private static final String ADMIN_UID = "admin-user-1";
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15.10-alpine")
            .withDatabaseName("boss_admin_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        initializeRestrictedSchema();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "boss_admin_api");
        registry.add("spring.datasource.password", () -> "admin_test_password");
    }

    private static void initializeRestrictedSchema() {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE ROLE boss_admin_api LOGIN PASSWORD 'admin_test_password' NOSUPERUSER NOBYPASSRLS");
            sql.execute("CREATE TABLE customer_groups (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text NOT NULL, slug text NOT NULL UNIQUE, status text NOT NULL DEFAULT 'active', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())");
            sql.execute("CREATE TABLE tenant_registry (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), customer_group_id uuid NOT NULL REFERENCES customer_groups(id), name text NOT NULL, slug text NOT NULL UNIQUE, status text NOT NULL DEFAULT 'provisioning', plan_tier text NOT NULL DEFAULT 'pilot', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())");
            sql.execute("CREATE TABLE legal_entities (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant_registry(id), name text NOT NULL, legal_name text NOT NULL, pan text, entity_type text, status text NOT NULL DEFAULT 'active', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())");
            sql.execute("CREATE TABLE physical_sites (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant_registry(id), legal_entity_id uuid NOT NULL REFERENCES legal_entities(id), gst_reg_id uuid, name text NOT NULL, code text NOT NULL, site_type text NOT NULL DEFAULT 'factory', status text NOT NULL DEFAULT 'active', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id, code))");
            sql.execute("CREATE TABLE tenant_onboarding_config (tenant_id uuid PRIMARY KEY REFERENCES tenant_registry(id), industry_type text NOT NULL, onboarding_completed_at timestamptz, onboarding_completed_by uuid)");
            sql.execute("CREATE TABLE platform_admin_users (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), firebase_uid text NOT NULL UNIQUE, email text NOT NULL UNIQUE, display_name text, status text NOT NULL DEFAULT 'active', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())");
            sql.execute("CREATE TABLE platform_audit_events (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), admin_id uuid REFERENCES platform_admin_users(id), entity_type text NOT NULL, entity_id uuid, action text NOT NULL, old_data jsonb, new_data jsonb, ip_address inet, created_at timestamptz NOT NULL DEFAULT now())");
            sql.execute("ALTER TABLE legal_entities ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE legal_entities FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY tenant_isolation ON legal_entities USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)");
            sql.execute("ALTER TABLE physical_sites ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE physical_sites FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY tenant_isolation ON physical_sites USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)");
            sql.execute("ALTER TABLE tenant_onboarding_config ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE tenant_onboarding_config FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY tenant_isolation ON tenant_onboarding_config USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)");
            sql.execute("GRANT USAGE ON SCHEMA public TO boss_admin_api");
            sql.execute("GRANT SELECT, INSERT, UPDATE ON customer_groups, tenant_registry, legal_entities, physical_sites, tenant_onboarding_config TO boss_admin_api");
            sql.execute("GRANT SELECT ON platform_admin_users TO boss_admin_api");
            sql.execute("GRANT INSERT ON platform_audit_events TO boss_admin_api");
            sql.execute("INSERT INTO platform_admin_users(id, firebase_uid, email) VALUES ('" + ADMIN_ID + "', '" + ADMIN_UID + "', 'admin@boss.local')");
            sql.execute("CREATE TABLE customer_invoices (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, amount numeric NOT NULL)");
            sql.execute("INSERT INTO customer_invoices(tenant_id, amount) VALUES ('00000000-0000-0000-0000-000000000099', 99)");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AdminTokenVerifier verifier;

    private JdbcTemplate privilegedJdbc() {
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres");
        return new JdbcTemplate(source);
    }

    @Test
    @Order(1)
    void provisionsControlPlaneRecordsWithinTenantRlsAndWritesAudit() throws Exception {
        when(verifier.verify("admin-token")).thenReturn(new AdminTokenVerifier.VerifiedAdminToken(ADMIN_UID, "admin@boss.local"));
        String body = """
                {"customerGroupName":"North Group","customerGroupSlug":"north-group","tenantName":"North Works","tenantSlug":"north-works","legalEntityName":"North Works Pvt Ltd","pan":"ABCDE1234F","entityType":"PRIVATE_LIMITED","siteCode":"PUNE-01","siteName":"Pune Factory","adminEmail":"owner@north.example","industryType":"FABRICATION_STEEL"}
                """;

        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "provision-001").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").isNotEmpty())
                .andExpect(jsonPath("$.seedingApplied").value(false));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_groups", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_registry WHERE status = 'provisioning'", Integer.class)).isEqualTo(1);
        JdbcTemplate privileged = privilegedJdbc();
        org.assertj.core.api.Assertions.assertThat(privileged.queryForObject("SELECT count(*) FROM legal_entities", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(privileged.queryForObject("SELECT count(*) FROM physical_sites", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(privileged.queryForObject("SELECT count(*) FROM platform_audit_events WHERE action = 'tenant.provisioned'", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM customer_invoices", Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @Order(2)
    void suspendsAndReactivatesTenantAndAuditsBothTransitions() throws Exception {
        when(verifier.verify("admin-token")).thenReturn(new AdminTokenVerifier.VerifiedAdminToken(ADMIN_UID, "admin@boss.local"));
        UUID tenantId = jdbc.queryForObject("SELECT id FROM tenant_registry WHERE slug = 'north-works'", UUID.class);

        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"billing review\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM tenant_registry WHERE id = ?", String.class, tenantId)).isEqualTo("active");
        org.assertj.core.api.Assertions.assertThat(privilegedJdbc().queryForObject(
                "SELECT count(*) FROM platform_audit_events WHERE entity_id = ? AND action IN ('tenant.suspended','tenant.reactivated')",
                Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    @Order(3)
    void rejectsCustomerFirebaseTokensOnEveryAdminRouteBeforeDatabaseAccess() throws Exception {
        when(verifier.verify("customer-token")).thenThrow(new in.boss.admin.security.FirebaseAdminTokenVerifier.CustomerProjectTokenException());
        String tenantId = "00000000-0000-0000-0000-000000000099";
        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/opening-balance/import").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    void rejectsMissingAndUnknownPlatformAdminAuthentication() throws Exception {
        when(verifier.verify(anyString())).thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad token"));
        mvc.perform(get("/admin/api/v1/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }
}
