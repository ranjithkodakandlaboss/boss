package in.boss.shared.outbox;

import in.boss.shared.context.BossContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes to outbox_events in the same transaction as the business operation.
 * The outbox relay (Cloud Scheduler + Cloud Tasks) reads pending rows and
 * publishes to Pub/Sub. Never publish directly to Pub/Sub.
 */
@Service
public class OutboxPublisher {

    private final JdbcTemplate jdbc;

    public OutboxPublisher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void publish(String aggregateType, UUID aggregateId,
                        String eventType, String payloadJson) {
        var ctx = BossContextHolder.get();
        UUID tenantId = ctx != null ? ctx.tenantId() : null;

        jdbc.update("""
                INSERT INTO outbox_events
                  (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """,
                tenantId,
                aggregateType,
                aggregateId,
                eventType,
                payloadJson);
    }
}
