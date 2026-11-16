package in.boss.shared.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules periodic execution of {@link OutboxRelayService#relay()} (issue #15).
 *
 * Automatic scheduling is enabled by default in production (matchIfMissing = true),
 * but can be disabled during tests via {@code boss.outbox.relay.scheduling.enabled=false}
 * while leaving {@link OutboxRelayService} available as a Spring bean for explicit invocation.
 */
@Component
@ConditionalOnProperty(name = "boss.outbox.relay.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelayService relayService;

    public OutboxRelayScheduler(OutboxRelayService relayService) {
        this.relayService = relayService;
    }

    @Scheduled(fixedDelayString = "${boss.outbox.relay.interval-ms:5000}")
    public void run() {
        relayService.relay();
    }
}
