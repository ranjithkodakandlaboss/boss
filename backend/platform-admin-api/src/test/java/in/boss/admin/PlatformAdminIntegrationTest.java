package in.boss.admin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.admin.security.AdminTokenVerifier;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class PlatformAdminIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PlatformAdminPostgres.ensure();
        registry.add("spring.datasource.url", PlatformAdminPostgres.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "boss_admin");
        registry.add("spring.datasource.password", () -> PlatformAdminPostgres.ADMIN_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("boss.admin.customer-workspace-url-template", () -> "http://{slug}.tenant.test");
        registry.add("boss.email.invitation-url-base", () -> "http://{slug}.tenant.test/activate");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate adminJdbc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AdminTokenVerifier verifier;

    void authenticateAdmin() {
        when(verifier.verify("admin-token")).thenReturn(new AdminTokenVerifier.VerifiedAdminToken(
                PlatformAdminPostgres.ADMIN_UID, "platform-admin@boss.test"));
    }

    JdbcTemplate privilegedJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                PlatformAdminPostgres.POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    String requestBody(String groupSlug, String tenantSlug, String industryType) {
        return requestBody(groupSlug, tenantSlug, industryType, null);
    }

    String requestBody(String groupSlug, String tenantSlug, String industryType, String adminEmail) {
        String emailJson = adminEmail == null ? "" : ",\"adminEmail\":\"" + adminEmail + "\"";
        return """
                {"customerGroupName":"Group %s","customerGroupSlug":"%s","tenantName":"Tenant %s","tenantSlug":"%s","legalEntityName":"Example Manufacturing Pvt Ltd","pan":"ABCDE1234F","entityType":"PRIVATE_LIMITED","siteCode":"SITE-01","siteName":"Factory","industryType":"%s"%s}
                """.formatted(groupSlug, groupSlug, tenantSlug, tenantSlug, industryType, emailJson);
    }

    JsonNode provision(String groupSlug, String tenantSlug, String industryType, String key) throws Exception {
        String response = mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(groupSlug, tenantSlug, industryType)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    void activate(UUID tenantId) throws Exception {
        mvc.perform(post("/admin/api/v1/tenants/{id}/activate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }
}
