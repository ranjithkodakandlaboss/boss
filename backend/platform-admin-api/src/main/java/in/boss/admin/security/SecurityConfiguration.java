package in.boss.admin.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    private static final List<String> DEFAULT_ORIGIN_PATTERNS = List.of(
            "http://127.0.0.1:5173",
            "http://localhost:5173",
            "https://*.run.app",
            "http://*.nip.io",
            "https://*.nip.io"
    );

    @Value("${boss.admin.cors.allowed-origins:}")
    private String additionalOrigins;

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> patterns = new ArrayList<>(DEFAULT_ORIGIN_PATTERNS);
        if (additionalOrigins != null && !additionalOrigins.isBlank()) {
            for (String origin : additionalOrigins.split(",")) {
                if (!origin.isBlank()) patterns.add(origin.trim());
            }
        }
        configuration.setAllowedOriginPatterns(patterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/admin/api/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain platformAdminFilterChain(HttpSecurity http, AdminAuthenticationFilter filter) throws Exception {
        return http.cors(cors -> {})
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**", "/error",
                                "/admin/api/v1/invites/**",
                                "/admin/api/v1/tenants/resolve", "/admin/api/v1/tenants/by-slug/**",
                                "/", "/index.html", "/assets/**", "/*.ico", "/*.svg", "/*.png", "/*.js", "/*.css"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
