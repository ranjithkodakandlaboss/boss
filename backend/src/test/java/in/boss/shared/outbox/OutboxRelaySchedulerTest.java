package in.boss.shared.outbox;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxRelaySchedulerTest {

    @Test
    void run_invokesRelayOnService() {
        OutboxRelayService service = mock(OutboxRelayService.class);
        OutboxRelayScheduler scheduler = new OutboxRelayScheduler(service);

        scheduler.run();

        verify(service).relay();
    }
}
