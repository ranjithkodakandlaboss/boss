package in.boss.shared.outbox;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Test-only configuration providing a mock {@link PubSubTemplate} bean when
 * Spring Cloud GCP Pub/Sub auto-configuration is excluded during tests.
 *
 * Ensures tests that do not exercise Pub/Sub directly can initialize the application
 * context without requiring real GCP credentials or connectivity.
 */
@AutoConfiguration
@ConditionalOnClass(PubSubTemplate.class)
public class TestPubSubConfiguration {

    @Bean
    @ConditionalOnMissingBean(PubSubTemplate.class)
    public PubSubTemplate pubSubTemplate() {
        return Mockito.mock(PubSubTemplate.class);
    }
}
