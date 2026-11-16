package in.boss.shared.outbox;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies transactional guarantees of OutboxPublisher (issue #15).
 *
 * Contract: outbox_events rows are written IN the same transaction as the
 * business operation. A rolled-back transaction must leave no orphaned outbox rows.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxTransactionTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;
    @MockBean PubSubTemplate pubSubTemplate;

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
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired PlatformTransactionManager txManager;

    static UUID tenantId;

    @BeforeAll
    static void provisionTenant(@Autowired JdbcTemplate jdbc) {
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES ('Outbox Tx Group','outbox-tx-grp') "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class));
        tenantId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) "
                + "VALUES (?, 'Outbox Tx Tenant', 'outbox-tx-tenant', 'active') "
                + "ON CONFLICT (slug) DO UPDATE SET status = 'active' RETURNING id::text",
                String.class, groupId));
    }

    @AfterEach
    void clearContext() {
        BossContextHolder.clear();
    }

    private void setContext() {
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, null));
    }

    @Test
    void outbox_writtenInSameTransaction_visibleAfterCommit() {
        setContext();
        UUID entityId = UUID.randomUUID();

        outboxPublisher.publish("ORDER", entityId, "CREATED", "{\"source\":\"OutboxTransactionTest\"}");

        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, entityId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void outbox_eventDoesNotSurviveRolledBackTransaction() {
        setContext();
        UUID entityId = UUID.randomUUID();
        AtomicReference<Exception> caught = new AtomicReference<>();

        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                outboxPublisher.publish("ORDER", entityId, "CREATED", "{}");
                status.setRollbackOnly();
                return null;
            });
        } catch (Exception e) {
            caught.set(e);
        }

        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, entityId);
        assertThat(count)
                .as("Outbox event must not survive a rolled-back transaction")
                .isEqualTo(0);
    }

    @Test
    void outbox_rowHasPendingStatus_untilRelay() {
        setContext();
        UUID entityId = UUID.randomUUID();

        outboxPublisher.publish("INVOICE", entityId, "ISSUED", "{}");

        String status = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id = ?",
                String.class, entityId);
        assertThat(status).isEqualTo("pending");
    }

    @Test
    void outbox_attemptsInitialisedToZero() {
        setContext();
        UUID entityId = UUID.randomUUID();

        outboxPublisher.publish("SHIPMENT", entityId, "DISPATCHED", "{}");

        int attempts = jdbc.queryForObject(
                "SELECT attempts FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, entityId);
        assertThat(attempts).isZero();
    }
}
