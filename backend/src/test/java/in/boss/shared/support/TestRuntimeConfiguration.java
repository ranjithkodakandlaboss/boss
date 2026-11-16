package in.boss.shared.support;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

/**
 * Test-only beans. Spring Boot 3.5 will not start a scheduler with pool size 0,
 * and the Pub/Sub auto-configuration stays disabled because tests have no GCP credentials.
 */
@Configuration
public class TestRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PubSubTemplate pubSubTemplate() {
        return mock(PubSubTemplate.class);
    }

    @Bean
    TaskScheduler taskScheduler() {
        return new IdleTaskScheduler();
    }

    private static final class IdleTaskScheduler implements TaskScheduler {
        private static final ScheduledFuture<?> IDLE = new IdleFuture();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return IDLE;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return IDLE;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return IDLE;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return IDLE;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return IDLE;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return IDLE;
        }
    }

    private static final class IdleFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
