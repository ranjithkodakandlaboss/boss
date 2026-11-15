package in.boss.shared.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class IdempotencyFilterConfig {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        var filter = new IdempotencyFilter(jdbc, objectMapper);
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(10);  // runs after security filter, before business logic
        registration.setName("idempotencyFilter");
        return registration;
    }
}
