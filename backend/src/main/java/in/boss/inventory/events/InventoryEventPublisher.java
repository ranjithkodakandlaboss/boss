package in.boss.inventory.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.shared.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InventoryEventPublisher {

    static final String LOT_CREATED = "inventory.lot.created";
    static final String MOVEMENT_CREATED = "inventory.stock_movement.created";

    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public InventoryEventPublisher(OutboxPublisher outboxPublisher, ObjectMapper objectMapper) {
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    public void lotCreated(UUID lotId, Object payload) {
        publish("LOT", lotId, LOT_CREATED, payload);
    }

    public void movementCreated(UUID movementId, Object payload) {
        publish("STOCK_MOVEMENT", movementId, MOVEMENT_CREATED, payload);
    }

    private void publish(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            outboxPublisher.publish(aggregateType, aggregateId, eventType,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise inventory outbox payload", e);
        }
    }
}
