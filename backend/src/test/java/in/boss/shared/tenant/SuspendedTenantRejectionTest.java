package in.boss.shared.tenant;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CI-gated integration test verifying that suspended tenants are rejected on all
 * Customer API routes with 403 Forbidden (issue #21 and DoD).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SuspendedTenantRejectionTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

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

    static UUID tenantId;
    static final String FIREBASE_UID = "uid-suspended-test";

    @BeforeAll
    static void setupTestData(@Autowired JdbcTemplate jdbc) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Suspended Test Group', 'susp-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));

        tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Suspended Test Tenant', 'susp-tenant', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));

        UUID userId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO app_users (tenant_id, firebase_uid, email, display_name, status) "
                + "VALUES (?, ?, 'owner@susp-tenant.com', 'Suspended Owner', 'active') "
                + "ON CONFLICT (tenant_id, email) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, tenantId, FIREBASE_UID));

        jdbc.update(
                "INSERT INTO user_roles (tenant_id, user_id, role) VALUES (?, ?, 'OWNER') "
                + "ON CONFLICT (tenant_id, user_id, role, site_id) DO NOTHING",
                tenantId, userId);
    }

    @AfterEach
    void resetTenantStatus() {
        jdbc.update("UPDATE tenant_registry SET status = 'active' WHERE id = ?", tenantId);
    }

    private void mockAuth() throws Exception {
        FirebaseToken mockToken = mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(firebaseAuth.verifyIdToken(anyString())).thenReturn(mockToken);
    }

    @Test
    void activeTenant_canAccessApi() throws Exception {
        mockAuth();

        mockMvc.perform(get("/api/v1/config/options/PAYMENT_MODES")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void suspendedTenant_receivesForbiddenOnReadEndpoint() throws Exception {
        mockAuth();

        // Suspend the tenant
        jdbc.update("UPDATE tenant_registry SET status = 'suspended' WHERE id = ?", tenantId);

        mockMvc.perform(get("/api/v1/config/options/PAYMENT_MODES")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("TENANT_SUSPENDED"));
    }

    @Test
    void suspendedTenant_receivesForbiddenOnMutationEndpoint() throws Exception {
        mockAuth();

        // Suspend the tenant
        jdbc.update("UPDATE tenant_registry SET status = 'suspended' WHERE id = ?", tenantId);

        mockMvc.perform(post("/api/v1/config/options/PAYMENT_MODES")
                        .header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CUSTOM_PM\",\"label\":\"Custom Mode\",\"sortOrder\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("TENANT_SUSPENDED"));
    }

    @Test
    void reactivatedTenant_resumesNormalAccess() throws Exception {
        mockAuth();

        // 1. Suspend
        jdbc.update("UPDATE tenant_registry SET status = 'suspended' WHERE id = ?", tenantId);
        mockMvc.perform(get("/api/v1/config/options/PAYMENT_MODES")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());

        // 2. Reactivate
        jdbc.update("UPDATE tenant_registry SET status = 'active' WHERE id = ?", tenantId);

        // 3. Resumes normal access immediately without caching delays
        mockMvc.perform(get("/api/v1/config/options/PAYMENT_MODES")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }
}
