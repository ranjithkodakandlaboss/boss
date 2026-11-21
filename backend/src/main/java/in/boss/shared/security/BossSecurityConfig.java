package in.boss.shared.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class BossSecurityConfig {

    private final FirebaseJwtFilter firebaseJwtFilter;

    public BossSecurityConfig(FirebaseJwtFilter firebaseJwtFilter) {
        this.firebaseJwtFilter = firebaseJwtFilter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Exact origins (always include localhost for dev)
        java.util.List<String> allowedOrigins = new java.util.ArrayList<>(
                List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        String extraOriginsEnv = System.getenv("CORS_ALLOWED_ORIGINS");
        if (extraOriginsEnv != null && !extraOriginsEnv.isBlank()) {
            for (String origin : extraOriginsEnv.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) allowedOrigins.add(trimmed);
            }
        }
        configuration.setAllowedOrigins(allowedOrigins);

        // Wildcard patterns (e.g. "http://*.34.54.120.120.nip.io" for multi-tenant subdomains)
        String patternsEnv = System.getenv("CORS_ALLOWED_ORIGIN_PATTERNS");
        if (patternsEnv != null && !patternsEnv.isBlank()) {
            for (String pattern : patternsEnv.split(",")) {
                String trimmed = pattern.trim();
                if (!trimmed.isEmpty()) configuration.addAllowedOriginPattern(trimmed);
            }
        }

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "SPRING_CSRF_PROTECTION_DISABLED",
            justification = "Stateless JWT API — no session cookies, so CSRF does not apply")
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> {})
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/activate").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(firebaseJwtFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")));
        return http.build();
    }
}
