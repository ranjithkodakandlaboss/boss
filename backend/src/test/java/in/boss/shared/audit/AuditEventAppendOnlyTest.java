package in.boss.shared.audit;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests verifying that audit_events is append-only (issue #14).
 *
 * V004 creates audit_events; V050 adds the blocking triggers:
 *   trg_audit_events_no_update — blocks UPDATE
 *   trg_audit_events_no_delete — blocks DELETE
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditEventAppendOnlyTest {

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

    @BeforeAll
    static void provisionTenant(@Autowired JdbcTemplate jdbc) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Audit Group','audit-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));
        tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Audit Tenant', 'audit-tenant', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));
    }

    @AfterEach
    void clearContext() {
        BossContextHolder.clear();
    }

    @Test
    void insert_auditEvent_succeeds() {
        UUID eventId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO audit_events (tenant_id, entity_type, entity_id, action) "
                + "VALUES (?, 'ORDER', gen_random_uuid(), 'CREATE') RETURNING id::text",
                String.class, tenantId));

        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE id = ?", Integer.class, eventId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void update_auditEvent_throwsTriggerException() {
        UUID eventId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO audit_events (tenant_id, entity_type, entity_id, action) "
                + "VALUES (?, 'ORDER', gen_random_uuid(), 'CREATE') RETURNING id::text",
                String.class, tenantId));

        assertThatThrownBy(() ->
                jdbc.update("UPDATE audit_events SET action = 'TAMPERED' WHERE id = ?", eventId))
                .hasMessageContaining("append-only");
    }

    @Test
    void delete_auditEvent_throwsTriggerException() {
        UUID eventId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO audit_events (tenant_id, entity_type, entity_id, action) "
                + "VALUES (?, 'ORDER', gen_random_uuid(), 'DELETE_ATTEMPT') RETURNING id::text",
                String.class, tenantId));

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM audit_events WHERE id = ?", eventId))
                .hasMessageContaining("append-only");
    }

    @Test
    void auditService_requiresBossContext() {
        // AuditService.recordEvent() must throw when no context is set
        BossContextHolder.clear();
        AuditService auditService = new AuditService(jdbc);

        assertThatThrownBy(() ->
                auditService.record("ORDER", UUID.randomUUID(), "CREATE", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BossContext");
    }

    @Test
    void auditService_insertsEventWithContext() {
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, null));
        AuditService auditService = new AuditService(jdbc);

        UUID entityId = UUID.randomUUID();
        auditService.record("INVOICE", entityId, "CREATE", null, "{\"amount\":100}");

        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND entity_type = 'INVOICE'",
                Integer.class, entityId);
        assertThat(count).isEqualTo(1);
    }
}
