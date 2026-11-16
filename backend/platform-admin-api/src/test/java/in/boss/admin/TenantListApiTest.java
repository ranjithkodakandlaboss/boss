package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantListApiTest extends PlatformAdminIntegrationTest {
    @Test
    void listsTenantsByStatusAndPageWithoutLegalIdentifiers() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode first = provision("list-a-" + suffix, "list-tenant-a-" + suffix, "FORGING", "list-a-" + suffix);
        JsonNode second = provision("list-b-" + suffix, "list-tenant-b-" + suffix, "CASTING", "list-b-" + suffix);
        activate(UUID.fromString(first.get("tenantId").asText()));

        String activePage = mvc.perform(get("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .param("status", "active")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode active = objectMapper.readTree(activePage);
        assertThat(active.get("content").toString()).contains(first.get("tenantId").asText());
        assertThat(active.get("content").toString()).doesNotContain(second.get("tenantId").asText());
        assertThat(activePage).doesNotContain("ABCDE1234F").doesNotContain("pan").doesNotContain("gstin");

        String paged = mvc.perform(get("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(paged);
        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("page").asInt()).isZero();
        Integer total = privilegedJdbc().queryForObject("SELECT count(*) FROM tenant_registry", Integer.class);
        assertThat(page.get("totalElements").asInt()).isEqualTo(total);

        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token").param("status", "archived-nope"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token").param("size", "0"))
                .andExpect(status().isBadRequest());
    }
}
