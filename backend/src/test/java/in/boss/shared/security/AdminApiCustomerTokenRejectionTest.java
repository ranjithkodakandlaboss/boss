package in.boss.shared.security;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that PLATFORM_ADMIN is rejected from user_roles (issues #97 and #20).
 *
 * V006 CHECK constraint on user_roles.role explicitly excludes PLATFORM_ADMIN.
 * This test verifies that any attempt to assign PLATFORM_ADMIN via user_roles fails
 * with a constraint violation — customer tokens can never carry PLATFORM_ADMIN authority
 * through the tenant role lookup in TenantContextResolver.
 *
 * Dependency chain: #97 (schema) → #19 (JWT filter) → #20 (RBAC)
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminApiCustomerTokenRejectionTest {

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

    @Autowired JdbcTemplate jdbc;

    static UUID tenantId;
    static UUID appUserId;

    @BeforeAll
    static void provisionData(@Autowired JdbcTemplate jdbc) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Admin Test Group','admin-test-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));
        tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Admin Test Tenant', 'admin-test-tenant', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));
        appUserId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO app_users (tenant_id, firebase_uid, email, display_name) "
                + "VALUES (?, 'test-uid-admin', 'admin@example.com', 'Test Admin') "
                + "ON CONFLICT (tenant_id, email) DO UPDATE SET display_name = EXCLUDED.display_name "
                + "RETURNING id::text",
                String.class, tenantId));
    }

    @Test
    void assignPlatformAdmin_toUserRoles_failsWithConstraintViolation() {
        // PLATFORM_ADMIN is NOT in the allowed role set for user_roles.
        // Any attempt to insert it must be rejected by the DB CHECK constraint (V006).
        assertThatThrownBy(() ->
                jdbc.update(
                        "INSERT INTO user_roles (tenant_id, user_id, role) VALUES (?, ?, 'PLATFORM_ADMIN')",
                        tenantId, appUserId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allTenantRoles_canBeAssigned_toUserRoles() {
        // Verify all valid tenant roles are insertable (regression guard)
        String[] validRoles = {
            "OWNER", "OPS_MANAGER", "SALES_MANAGER", "PURCHASE_MANAGER",
            "PRODUCTION_SUPERVISOR", "STORE_KEEPER", "ACCOUNTS_STAFF",
            "QUALITY_INSPECTOR", "FIELD_WORKER"
        };

        for (String role : validRoles) {
            UUID userId = UUID.fromString(jdbc.queryForObject(
                    "INSERT INTO app_users (tenant_id, firebase_uid, email, display_name) "
                    + "VALUES (?, ?, ?, ?) RETURNING id::text",
                    String.class, tenantId,
                    "uid-" + role.toLowerCase(), role.toLowerCase() + "@example.com", role));

            // Should not throw
            jdbc.update("INSERT INTO user_roles (tenant_id, user_id, role) VALUES (?, ?, ?)",
                    tenantId, userId, role);
        }
    }

    @Test
    void platformAdmin_notReturned_byTenantContextResolver() {
        // Verify that even if someone manually inserts PLATFORM_ADMIN (which the DB prevents),
        // the CHECK constraint ensures it cannot exist in user_roles.
        // This covers the dependency: #97 → #19 (TenantContextResolver only reads user_roles)

        // Check that the table's CHECK constraint is enforced by the database
        int countBeforeAttempt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE tenant_id = ? AND role = 'PLATFORM_ADMIN'",
                Integer.class, tenantId);

        try {
            jdbc.update(
                    "INSERT INTO user_roles (tenant_id, user_id, role) VALUES (?, ?, 'PLATFORM_ADMIN')",
                    tenantId, appUserId);
        } catch (DataIntegrityViolationException ignored) {
            // expected
        }

        int countAfterAttempt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE tenant_id = ? AND role = 'PLATFORM_ADMIN'",
                Integer.class, tenantId);

        // No PLATFORM_ADMIN rows must exist in user_roles — constraint must have blocked it
        org.junit.jupiter.api.Assertions.assertEquals(countBeforeAttempt, countAfterAttempt,
                "PLATFORM_ADMIN must not be insertable into user_roles");
    }
}
