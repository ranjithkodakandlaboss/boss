package in.boss.shared.outbox;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that OutboxRelayService publishes to Pub/Sub and marks rows as published (issue #15).
 *
 * Uses mocked JdbcTemplate and PubSubTemplate to isolate relay logic from infrastructure.
 * Idempotency: relay calls publish exactly once per pending row.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayPublishTest {

    @Mock JdbcTemplate jdbc;
    @Mock PubSubTemplate pubSubTemplate;

    @InjectMocks OutboxRelayService relay;

    @Test
    void relay_publishesPendingRow_toPubSub() throws Exception {
        UUID rowId = UUID.randomUUID();
        Map<String, Object> row = Map.of(
                "id", rowId,
                "aggregate_type", "ORDER",
                "event_type", "CREATED",
                "payload", "{\"orderId\":\"123\"}");

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));
        when(pubSubTemplate.publish(eq("ORDER.CREATED"), eq("{\"orderId\":\"123\"}")))
                .thenReturn(CompletableFuture.completedFuture("msg-id-1"));

        relay.relay();

        verify(pubSubTemplate).publish("ORDER.CREATED", "{\"orderId\":\"123\"}");
    }

    @Test
    void relay_marksRowPublished_afterSuccessfulPublish() throws Exception {
        UUID rowId = UUID.randomUUID();
        Map<String, Object> row = Map.of(
                "id", rowId,
                "aggregate_type", "INVOICE",
                "event_type", "ISSUED",
                "payload", "{}");

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));
        when(pubSubTemplate.publish(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("msg-id-2"));

        relay.relay();

        verify(jdbc).update(contains("status = 'published'"), eq(rowId));
    }

    @Test
    void relay_marksRowFailed_afterMaxAttempts() {
        UUID rowId = UUID.randomUUID();
        Map<String, Object> row = Map.of(
                "id", rowId,
                "aggregate_type", "SHIPMENT",
                "event_type", "DISPATCHED",
                "payload", "{}");

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));
        when(pubSubTemplate.publish(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Pub/Sub unavailable")));
        // Simulate attempts = MAX_ATTEMPTS (3)
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(rowId)))
                .thenReturn(3);

        relay.relay();

        verify(jdbc).update(contains("status = 'failed'"), eq(rowId));
    }

    @Test
    void relay_doesNothing_whenNoPendingRows() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        relay.relay();

        verifyNoInteractions(pubSubTemplate);
    }

    @Test
    void relay_publishesTopicAsAggregateTypeDotEventType() throws Exception {
        UUID rowId = UUID.randomUUID();
        Map<String, Object> row = Map.of(
                "id", rowId,
                "aggregate_type", "PURCHASE_ORDER",
                "event_type", "APPROVED",
                "payload", "{}");

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row));
        when(pubSubTemplate.publish(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("msg-id-3"));

        relay.relay();

        verify(pubSubTemplate).publish("PURCHASE_ORDER.APPROVED", "{}");
    }
}
