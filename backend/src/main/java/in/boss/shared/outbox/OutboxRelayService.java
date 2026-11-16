package in.boss.shared.outbox;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relay job: reads pending rows from outbox_events and publishes to Pub/Sub (issue #15).
 *
 * Uses polling + optimistic row-lock (UPDATE … RETURNING) to claim rows,
 * then publishes to the "{aggregateType}.{eventType}" topic.
 * On success: marks row status='published'.
 * On failure: increments attempts; marks status='failed' after max retries.
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final PubSubTemplate pubSubTemplate;

    public OutboxRelayService(JdbcTemplate jdbc, PubSubTemplate pubSubTemplate) {
        this.jdbc = jdbc;
        this.pubSubTemplate = pubSubTemplate;
    }

    @Transactional
    public void relay() {
        List<Map<String, Object>> rows = claimBatch();
        if (rows.isEmpty()) return;

        log.debug("Relaying {} outbox events", rows.size());
        for (Map<String, Object> row : rows) {
            publish(row);
        }
    }

    private List<Map<String, Object>> claimBatch() {
        return jdbc.queryForList(
                "UPDATE outbox_events SET attempts = attempts + 1 "
                + "WHERE id IN ( "
                + "  SELECT id FROM outbox_events "
                + "  WHERE status = 'pending' AND attempts < ? "
                + "  ORDER BY created_at "
                + "  LIMIT ? "
                + "  FOR UPDATE SKIP LOCKED "
                + ") RETURNING id, aggregate_type, event_type, payload::text AS payload",
                MAX_ATTEMPTS, BATCH_SIZE);
    }

    private void publish(Map<String, Object> row) {
        UUID rowId = (UUID) row.get("id");
        String topic = row.get("aggregate_type") + "." + row.get("event_type");
        String payload = (String) row.get("payload");

        try {
            pubSubTemplate.publish(topic, payload).get();
            markPublished(rowId);
        } catch (Exception e) {
            log.warn("Failed to publish outbox row {}: {}", rowId, e.getMessage());
            int attempts = jdbc.queryForObject(
                    "SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, rowId);
            if (attempts >= MAX_ATTEMPTS) {
                markFailed(rowId);
            }
        }
    }

    private void markPublished(UUID rowId) {
        jdbc.update(
                "UPDATE outbox_events SET status = 'published', published_at = NOW() WHERE id = ?",
                rowId);
    }

    private void markFailed(UUID rowId) {
        jdbc.update("UPDATE outbox_events SET status = 'failed' WHERE id = ?", rowId);
    }
}
