package in.boss.masterdata.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.shared.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MasterDataEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MasterDataEventPublisher.class);

    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public MasterDataEventPublisher(OutboxPublisher outboxPublisher, ObjectMapper objectMapper) {
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    public void publishEvent(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            outboxPublisher.publish(aggregateType, aggregateId, eventType, payloadJson);
            log.info("Published outbox event aggregateType={}, aggregateId={}, eventType={}",
                    aggregateType, aggregateId, eventType);
        } catch (Exception e) {
            log.error("Failed to publish outbox event aggregateType={}, eventType={}", aggregateType, eventType, e);
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }
}
