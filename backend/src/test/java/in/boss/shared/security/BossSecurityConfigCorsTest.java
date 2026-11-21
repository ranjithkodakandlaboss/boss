package in.boss.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class BossSecurityConfigCorsTest {

    @Test
    void corsConfigurationAllowsSubdomainsAndLocalhost() {
        BossSecurityConfig config = new BossSecurityConfig(null);
        CorsConfigurationSource source = config.corsConfigurationSource();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/rfqs");
        CorsConfiguration cors = source.getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.checkOrigin("http://acme.34.54.120.120.nip.io")).isEqualTo("http://acme.34.54.120.120.nip.io");
        assertThat(cors.checkOrigin("https://acme.34.54.120.120.nip.io")).isEqualTo("https://acme.34.54.120.120.nip.io");
        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("https://boss-dev.run.app")).isEqualTo("https://boss-dev.run.app");
        assertThat(cors.checkOrigin("http://evil.com")).isNull();
        assertThat(cors.getAllowedMethods()).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(cors.getExposedHeaders()).contains("Authorization", "Idempotency-Key", "X-Correlation-ID");
    }
}
