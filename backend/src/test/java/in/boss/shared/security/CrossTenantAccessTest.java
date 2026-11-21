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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies tenant membership enforcement at the data layer and RLS isolation.
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

    private DataSource nonSuperuserDataSource;

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
    void suspendedOrDeactivatedUser_isInactive() {
        UUID groupId = insertCustomerGroup();
        UUID tenant = insertTenant(groupId, "tenant-susp-" + UUID.randomUUID());
        String uidSusp = "uid-susp-" + UUID.randomUUID();
        String uidDeact = "uid-deact-" + UUID.randomUUID();

        jdbc.update("INSERT INTO app_users (tenant_id, firebase_uid, email, status) VALUES (?, ?, ?, 'suspended')",
                tenant, uidSusp, "susp@example.test");
        jdbc.update("INSERT INTO app_users (tenant_id, firebase_uid, email, status) VALUES (?, ?, ?, 'deactivated')",
                tenant, uidDeact, "deact@example.test");

        assertThat(tenantContextResolver.isInactive(uidSusp)).isTrue();
        assertThat(tenantContextResolver.isInactive(uidDeact)).isTrue();
        assertThat(tenantContextResolver.resolve(uidSusp)).isNull();
        assertThat(tenantContextResolver.resolve(uidDeact)).isNull();
    }

    @Test
    void bossResolveFunction_returnsAtMostOneTenantPerUid() {
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

        assertThat(rowsA).hasSize(1);
        assertThat(rowsA.get(0).get("out_tenant_id").toString()).isEqualTo(tenantA.toString());

        assertThat(rowsB).hasSize(1);
        assertThat(rowsB.get(0).get("out_tenant_id").toString()).isEqualTo(tenantB.toString());
    }

    @Test
    void rlsIsolation_tenantAContextCannotReadTenantBData() {
        UUID groupId = insertCustomerGroup();
        UUID tenantA = insertTenant(groupId, "rls-a-" + UUID.randomUUID());
        UUID tenantB = insertTenant(groupId, "rls-b-" + UUID.randomUUID());

        UUID siteA = insertSite(tenantA, "Site A");
        UUID siteB = insertSite(tenantB, "Site B");

        // Running queries as a non-superuser connection where RLS is strictly enforced
        List<Map<String, Object>> seenByA = queryAsNonSuperuser(tenantA.toString(),
                "SELECT id FROM physical_sites WHERE id IN ('" + siteA + "', '" + siteB + "')");
        assertThat(seenByA).hasSize(1);
        assertThat(seenByA.get(0).get("id").toString()).isEqualTo(siteA.toString());

        List<Map<String, Object>> seenByB = queryAsNonSuperuser(tenantB.toString(),
                "SELECT id FROM physical_sites WHERE id IN ('" + siteA + "', '" + siteB + "')");
        assertThat(seenByB).hasSize(1);
        assertThat(seenByB.get(0).get("id").toString()).isEqualTo(siteB.toString());

        List<Map<String, Object>> seenByEmpty = queryAsNonSuperuser("",
                "SELECT id FROM physical_sites WHERE id IN ('" + siteA + "', '" + siteB + "')");
        assertThat(seenByEmpty).isEmpty();
    }

    private DataSource getNonSuperuserDataSource() {
        if (nonSuperuserDataSource == null) {
            jdbc.execute("DO $$ BEGIN " +
                    "IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'boss_app_user_security') THEN " +
                    "  CREATE ROLE boss_app_user_security WITH LOGIN PASSWORD 'boss_test_pass'; " +
                    "END IF; " +
                    "END $$;");
            jdbc.execute("GRANT ALL PRIVILEGES ON DATABASE boss_test TO boss_app_user_security");
            jdbc.execute("GRANT ALL ON SCHEMA public TO boss_app_user_security");
            jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO boss_app_user_security");
            jdbc.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO boss_app_user_security");
            jdbc.execute("GRANT ALL ON ALL FUNCTIONS IN SCHEMA public TO boss_app_user_security");

            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("org.postgresql.Driver");
            ds.setUrl(postgres.getJdbcUrl());
            ds.setUsername("boss_app_user_security");
            ds.setPassword("boss_test_pass");
            nonSuperuserDataSource = ds;
        }
        return nonSuperuserDataSource;
    }

    private List<Map<String, Object>> queryAsNonSuperuser(String tenantId, String sql) {
        try (Connection conn = getNonSuperuserDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT set_config('app.current_tenant_id', '" + tenantId + "', false)");
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    List<Map<String, Object>> results = new ArrayList<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        results.add(row);
                    }
                    return results;
                }
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

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

    private UUID insertLegalEntity(UUID tenantId) {
        return jdbc.queryForObject(
                "INSERT INTO legal_entities (tenant_id, name, legal_name, pan, entity_type) VALUES (?, 'Test LE', 'Test LE Ltd', 'ABCDE1234F', 'PRIVATE_LIMITED') RETURNING id",
                UUID.class, tenantId);
    }

    private UUID insertSite(UUID tenantId, String name) {
        UUID leId = insertLegalEntity(tenantId);
        return jdbc.queryForObject(
                "INSERT INTO physical_sites (tenant_id, name, code, site_type, legal_entity_id) " +
                "VALUES (?, ?, ?, 'factory', ?) RETURNING id",
                UUID.class, tenantId, name, "SITE-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), leId);
    }
}
