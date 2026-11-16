package in.boss.shared.idempotency;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class IdempotencyFilterConfig {

    @Bean
    public IdempotencyFilter idempotencyFilter(JdbcTemplate jdbc) {
        return new IdempotencyFilter(jdbc);
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(10);
        registration.setName("idempotencyFilter");
        return registration;
    }
}
