package in.boss.admin;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.boss.admin.domain.TenantManagementService;
import in.boss.admin.security.AdminAuthenticationFilter;
import in.boss.admin.security.AdminTokenVerifier;
import in.boss.admin.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, AdminAuthenticationFilter.class})
class AdminApiCorsTest {

    @Autowired MockMvc mvc;
    @MockitoBean AdminTokenVerifier verifier;
    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean TenantManagementService tenantManagementService;

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:5173", "http://localhost:5173"})
    void allowsLocalFrontendGetPreflightWithRequiredHeaders(String origin) throws Exception {
        mvc.perform(options("/admin/api/v1/tenants")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Methods", containsStringIgnoringCase("GET")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("Authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("Content-Type")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("Idempotency-Key")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:5173", "http://localhost:5173"})
    void allowsLocalFrontendPostPreflight(String origin) throws Exception {
        mvc.perform(options("/admin/api/v1/tenants")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Methods", containsStringIgnoringCase("POST")));
    }

    @Test
    void rejectsPreflightFromUnconfiguredOrigin() throws Exception {
        mvc.perform(options("/admin/api/v1/tenants")
                        .header("Origin", "http://malicious-origin.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void allowedOriginDoesNotMakeUnauthenticatedApiRequestsPublic() throws Exception {
        mvc.perform(get("/admin/api/v1/tenants").header("Origin", "http://127.0.0.1:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
    }
}
