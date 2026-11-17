package in.boss.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import in.boss.identity.domain.CustomerIdentityService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class JourneyOneIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("boss_test")
            .withUsername("boss")
            .withPassword("boss");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    static UUID tenantA;
    static UUID tenantB;

    @BeforeAll
    static void tenants(@Autowired JdbcTemplate jdbc) {
        tenantA = provision(jdbc, "journey-a");
        tenantB = provision(jdbc, "journey-b");
    }

    @Test
    void customerActivatesSignsInSavesCompanyAndCannotSeeAnotherTenant() throws Exception {
        String tokenA = "activate-a-" + UUID.randomUUID();
        String tokenB = "activate-b-" + UUID.randomUUID();
        invite(tenantA, "owner-a@example.test", tokenA);
        invite(tenantB, "owner-b@example.test", tokenB);
        when(firebaseAuth.createUser(any())).thenAnswer(invocation -> {
            UserRecord record = mock(UserRecord.class);
            when(record.getUid()).thenReturn("uid-" + UUID.randomUUID());
            return record;
        });

        JsonNode userA = activate(tokenA, "owner-a-pass", "Owner A");
        JsonNode userB = activate(tokenB, "owner-b-pass", "Owner B");
        stubSession("session-a", userA.get("firebaseUid").asText());
        stubSession("session-b", userB.get("firebaseUid").asText());

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer session-a"))
                .andExpect(status().isOk());
        String company = """
                {"companyName":"North Works","tradingName":"North","legalName":"North Works Pvt Ltd","entityType":"PRIVATE_LIMITED","pan":"ABCDE1234F","addressLine":"1 Mill Road","city":"Pune","state":"Maharashtra","email":"office@north.test","phone":"9999999999","gstNotApplicable":true,"sites":[{"name":"Pune Plant","code":"PUNE","siteType":"factory","city":"Pune","state":"Maharashtra"}],"stores":[{"siteCode":"PUNE","name":"Raw Store","code":"RAW","storeType":"raw_material"}]}
                """;
        mvc.perform(put("/api/v1/company").header("Authorization", "Bearer session-a")
                        .header("Idempotency-Key", "company-" + tenantA)
                        .contentType(MediaType.APPLICATION_JSON).content(company))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/onboarding/complete").header("Authorization", "Bearer session-a")
                        .header("Idempotency-Key", "complete-" + tenantA)
                        .contentType(MediaType.APPLICATION_JSON).content(company))
                .andExpect(status().isOk());

        JsonNode saved = json.readTree(mvc.perform(get("/api/v1/company").header("Authorization", "Bearer session-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.get("legalName").asText()).isEqualTo("North Works Pvt Ltd");
        assertThat(saved.get("city").asText()).isEqualTo("Pune");
        JsonNode onboarding = json.readTree(mvc.perform(get("/api/v1/onboarding").header("Authorization", "Bearer session-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(onboarding.get("completed").asBoolean()).isTrue();

        JsonNode other = json.readTree(mvc.perform(get("/api/v1/company").header("Authorization", "Bearer session-b"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(other.get("legalName").asText()).isNotEqualTo("North Works Pvt Ltd");

        jdbc.update("UPDATE tenant_registry SET status = 'suspended' WHERE id = ?", tenantA);
        mvc.perform(get("/api/v1/company").header("Authorization", "Bearer session-a"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_users SET status = 'suspended' WHERE tenant_id = ?", tenantB);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer session-b"))
                .andExpect(status().isForbidden());
    }

    @Test
    void inviteLookupExecuteIsLimitedToAppAndPlatformAdminRoles() {
        String function = "public.boss_lookup_invite_by_hash(text)";
        assertThat(privilege("public", function)).isFalse();
        assertThat(privilege("boss_app_role", function)).isTrue();
        assertThat(privilege("boss_platform_admin_role", function)).isTrue();

        jdbc.execute("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'invite_lookup_app') THEN
                    CREATE ROLE invite_lookup_app NOLOGIN NOSUPERUSER NOBYPASSRLS INHERIT;
                  END IF;
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'invite_lookup_admin') THEN
                    CREATE ROLE invite_lookup_admin NOLOGIN NOSUPERUSER NOBYPASSRLS INHERIT;
                  END IF;
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'invite_lookup_other') THEN
                    CREATE ROLE invite_lookup_other NOLOGIN NOSUPERUSER NOBYPASSRLS INHERIT;
                  END IF;
                END $$
                """);
        jdbc.execute("GRANT boss_app_role TO invite_lookup_app");
        jdbc.execute("GRANT boss_platform_admin_role TO invite_lookup_admin");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO invite_lookup_other");

        assertThat(countAs("invite_lookup_app")).isZero();
        assertThat(countAs("invite_lookup_admin")).isZero();
        assertThatThrownBy(() -> countAs("invite_lookup_other"))
                .cause()
                .hasMessageContaining("permission denied for function boss_lookup_invite_by_hash");
    }

    private boolean privilege(String role, String function) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_function_privilege(?, ?, 'EXECUTE')", Boolean.class, role, function));
    }

    private int countAs(String role) {
        return jdbc.execute((Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + role);
                try (ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM boss_lookup_invite_by_hash("
                                + "'0000000000000000000000000000000000000000000000000000000000000000')")) {
                    rows.next();
                    return rows.getInt(1);
                } finally {
                    statement.execute("RESET ROLE");
                }
            }
        });
    }

    private JsonNode activate(String token, String password, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/activate")
                        .header("Idempotency-Key", "activate-" + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\",\"displayName\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void stubSession(String session, String uid) throws Exception {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(firebaseAuth.verifyIdToken(session)).thenReturn(token);
    }

    private void invite(UUID tenantId, String email, String token) {
        jdbc.update("INSERT INTO invite_tokens(tenant_id, email, role, token_hash, status, expires_at) "
                        + "VALUES (?, ?, 'OWNER', ?, 'pending', now() + interval '7 days')",
                tenantId, email, CustomerIdentityService.sha256(token));
    }

    private static UUID provision(JdbcTemplate jdbc, String slug) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups(name, slug) VALUES (?, ?) RETURNING id::text",
                String.class, slug + " group", slug + "-group"));
        UUID tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry(customer_group_id, name, slug, status) VALUES (?, ?, ?, 'active') RETURNING id::text",
                String.class, groupId, slug + " tenant", slug));
        UUID legalId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO legal_entities(tenant_id, name, legal_name) VALUES (?, ?, ?) RETURNING id::text",
                String.class, tenantId, slug + " entity", slug + " entity"));
        jdbc.update("INSERT INTO physical_sites(tenant_id, legal_entity_id, name, code) VALUES (?, ?, 'Plant', ?)",
                tenantId, legalId, slug.toUpperCase());
        jdbc.update("INSERT INTO tenant_onboarding_config(tenant_id, industry_type) VALUES (?, 'GENERAL_MANUFACTURING')",
                tenantId);
        return tenantId;
    }
}
