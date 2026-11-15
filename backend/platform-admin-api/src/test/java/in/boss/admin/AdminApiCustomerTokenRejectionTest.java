package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.admin.security.AdminTokenVerifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AdminApiCustomerTokenRejectionTest {
    private static final String ADMIN_UID = "test-platform-admin";
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ADMIN_PASSWORD = "test-only-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15.10-alpine")
            .withDatabaseName("boss_admin_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        migrateAndCreateRestrictedRole();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "boss_admin");
        registry.add("spring.datasource.password", () -> ADMIN_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    private static void migrateAndCreateRestrictedRole() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE ROLE boss_admin LOGIN PASSWORD '" + ADMIN_PASSWORD + "' NOSUPERUSER NOBYPASSRLS INHERIT");
            sql.execute("GRANT USAGE ON SCHEMA public TO boss_admin");
            sql.execute("GRANT INSERT ON customer_groups TO boss_admin");
            sql.execute("GRANT SELECT(id) ON customer_groups TO boss_admin");
            sql.execute("GRANT SELECT, INSERT ON tenant_registry TO boss_admin");
            sql.execute("GRANT UPDATE(status, updated_at) ON tenant_registry TO boss_admin");
            sql.execute("GRANT INSERT ON legal_entities, physical_sites TO boss_admin");
            sql.execute("GRANT SELECT(id) ON legal_entities, physical_sites TO boss_admin");
            sql.execute("GRANT SELECT(id) ON tenant_registry TO boss_admin");
            sql.execute("GRANT SELECT, INSERT ON tenant_onboarding_config TO boss_admin");
            sql.execute("GRANT SELECT ON platform_admin_users TO boss_admin");
            sql.execute("GRANT INSERT ON platform_audit_events TO boss_admin");
            sql.execute("INSERT INTO platform_admin_users(id, firebase_uid, email) " +
                    "VALUES ('" + ADMIN_ID + "', '" + ADMIN_UID + "', 'platform-admin@boss.test')");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate adminJdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired javax.sql.DataSource adminDataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AdminTokenVerifier verifier;

    private JdbcTemplate privilegedJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    private void authenticateAdmin() {
        when(verifier.verify("admin-token"))
                .thenReturn(new AdminTokenVerifier.VerifiedAdminToken(ADMIN_UID, "platform-admin@boss.test"));
    }

    private String requestBody(String groupSlug, String tenantSlug, String industryType) {
        return """
                {"customerGroupName":"Group %s","customerGroupSlug":"%s","tenantName":"Tenant %s","tenantSlug":"%s","legalEntityName":"Example Manufacturing Pvt Ltd","pan":"ABCDE1234F","entityType":"PRIVATE_LIMITED","siteCode":"SITE-01","siteName":"Factory","adminEmail":"owner@example.test","industryType":"%s"}
                """.formatted(groupSlug, groupSlug, tenantSlug, tenantSlug, industryType);
    }

    private JsonNode provision(String groupSlug, String tenantSlug, String industryType, String key) throws Exception {
        String response = mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(groupSlug, tenantSlug, industryType)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void rejectsCustomerFirebaseTokensAndAcceptsOnlyRegisteredActivePlatformAdmins() throws Exception {
        when(verifier.verify("customer-token")).thenThrow(
                new in.boss.admin.security.FirebaseAdminTokenVerifier.CustomerProjectTokenException());
        when(verifier.verify("unknown-admin-token"))
                .thenReturn(new AdminTokenVerifier.VerifiedAdminToken("unknown-uid", "unknown@example.test"));

        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/suspend")
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/reactivate")
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/admin/api/v1/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer unknown-admin-token"))
                .andExpect(status().isUnauthorized());

        authenticateAdmin();
        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void provisionsAgainstTheMigratedSchemaAndWritesAppendOnlyAuditWithoutBusinessTableAccess() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("group-" + suffix, "tenant-" + suffix,
                "FABRICATION_STEEL", "provision-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());

        assertThat(provisioned.get("seedingApplied").asBoolean()).isFalse();
        assertThat(privilegedJdbc().queryForObject(
                "SELECT count(*) FROM platform_audit_events WHERE entity_id = ? AND action = 'tenant.provisioned'",
                Integer.class, tenantId)).isEqualTo(1);
        assertThatThrownBy(() -> adminJdbc.queryForObject("SELECT count(*) FROM items", Integer.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThatThrownBy(() -> privilegedJdbc().update(
                "UPDATE platform_audit_events SET action = 'tampered' WHERE entity_id = ?", tenantId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void duplicateGroupOrTenantSlugsReturnConflictAndDoNotLeavePartialGroupRows() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        provision("group-a-" + suffix, "tenant-a-" + suffix, "FORGING", "key-a-" + suffix);

        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "key-duplicate-group-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("group-a-" + suffix, "tenant-b-" + suffix, "CASTING")))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "key-duplicate-tenant-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("group-b-" + suffix, "tenant-a-" + suffix, "CASTING")))
                .andExpect(status().isConflict());

        assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM customer_groups WHERE slug = ?",
                Integer.class, "group-b-" + suffix)).isZero();
    }

    @Test
    void auditFailureRollsBackGroupTenantEntitySiteAndOnboardingRows() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JdbcTemplate privileged = privilegedJdbc();
        int entitiesBefore = privileged.queryForObject("SELECT count(*) FROM legal_entities", Integer.class);
        int sitesBefore = privileged.queryForObject("SELECT count(*) FROM physical_sites", Integer.class);
        int auditsBefore = privileged.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class);
        privileged.execute("""
                CREATE OR REPLACE FUNCTION test_fail_tenant_provision_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.action = 'tenant.provisioned' THEN
                    RAISE EXCEPTION 'forced audit write failure';
                  END IF;
                  RETURN NEW;
                END $$
                """);
        privileged.execute("CREATE TRIGGER test_fail_tenant_provision_audit BEFORE INSERT ON platform_audit_events " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_tenant_provision_audit()");
        try {
            mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "rollback-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("group-rollback-" + suffix, "tenant-rollback-" + suffix,
                                    "ELECTRONICS_ASSEMBLY")))
                    .andExpect(status().is5xxServerError());
        } finally {
            privileged.execute("DROP TRIGGER test_fail_tenant_provision_audit ON platform_audit_events");
            privileged.execute("DROP FUNCTION test_fail_tenant_provision_audit()");
        }

        assertThat(privileged.queryForObject("SELECT count(*) FROM customer_groups WHERE slug = ?",
                Integer.class, "group-rollback-" + suffix)).isZero();
        assertThat(privileged.queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "tenant-rollback-" + suffix)).isZero();
        assertThat(privileged.queryForObject("SELECT count(*) FROM legal_entities", Integer.class)).isEqualTo(entitiesBefore);
        assertThat(privileged.queryForObject("SELECT count(*) FROM physical_sites", Integer.class)).isEqualTo(sitesBefore);
        assertThat(privileged.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class)).isEqualTo(auditsBefore);
    }

    @Test
    void suspendAndReactivateTransitionsAreAuditedAndInvalidIdsAreRejected() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("group-state-" + suffix, "tenant-state-" + suffix,
                "PRECISION_MACHINING", "state-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());

        mvc.perform(post("/admin/api/v1/tenants/not-a-uuid/suspend")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/reactivate")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"billing review\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"repeat\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        assertThat(privilegedJdbc().queryForObject(
                "SELECT count(*) FROM platform_audit_events WHERE entity_id = ? " +
                        "AND action IN ('tenant.suspended','tenant.reactivated')", Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    void listingPaginatesAndRlsContextDoesNotLeakAcrossRowsOrPooledRequests() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode first = provision("group-list-a-" + suffix, "tenant-list-a-" + suffix,
                "FORGING", "list-a-" + suffix);
        JsonNode second = provision("group-list-b-" + suffix, "tenant-list-b-" + suffix,
                "CASTING", "list-b-" + suffix);
        UUID firstId = UUID.fromString(first.get("tenantId").asText());
        UUID secondId = UUID.fromString(second.get("tenantId").asText());

        withTenantContext(firstId, "FORGING");
        withTenantContext(secondId, "CASTING");
        assertThat(adminJdbc.queryForList("SELECT tenant_id FROM tenant_onboarding_config", UUID.class)).isEmpty();

        int connectionIdBefore = backendPid();
        mvc.perform(get("/admin/api/v1/tenants?page=0&size=1").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/admin/api/v1/tenants?page=1&size=1").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));

        String allTenants = mvc.perform(get("/admin/api/v1/tenants?size=100")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(allTenants).get("content");
        assertThat(StreamSupport.stream(content.spliterator(), false)
                .anyMatch(row -> row.get("id").asText().equals(firstId.toString())
                        && row.get("industryType").asText().equals("FORGING"))).isTrue();
        assertThat(StreamSupport.stream(content.spliterator(), false)
                .anyMatch(row -> row.get("id").asText().equals(secondId.toString())
                        && row.get("industryType").asText().equals("CASTING"))).isTrue();
        assertThat(backendPid()).isEqualTo(connectionIdBefore);
        assertThat(currentTenantSetting()).isNull();
        assertThat(adminJdbc.queryForList("SELECT tenant_id FROM tenant_onboarding_config", UUID.class)).isEmpty();
    }

    private void withTenantContext(UUID tenantId, String expectedIndustry) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            adminJdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)",
                    String.class, tenantId.toString());
            assertThat(adminJdbc.queryForObject("SELECT industry_type FROM tenant_onboarding_config WHERE tenant_id = ?",
                    String.class, tenantId)).isEqualTo(expectedIndustry);
        });
    }

    private int backendPid() throws Exception {
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    private String currentTenantSetting() throws Exception {
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_setting('app.current_tenant_id', true)")) {
            result.next();
            return result.getString(1);
        }
    }
}
