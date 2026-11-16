package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ReactivationTest extends PlatformAdminIntegrationTest {
    @Test
    void reactivatesOnlyASuspendedTenant() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("re-group-" + suffix, "re-tenant-" + suffix, "ELECTRONICS_ASSEMBLY", "re-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());

        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());
        activate(tenantId);
        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"pause\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/api/v1/tenants/{id}/reactivate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict());

        assertThat(privilegedJdbc().queryForObject("SELECT status FROM tenant_registry WHERE id = ?", String.class, tenantId))
                .isEqualTo("active");
        assertThat(privilegedJdbc().queryForObject(
                "SELECT count(*) FROM platform_audit_events WHERE entity_id = ? AND action = 'tenant.reactivated'",
                Integer.class, tenantId)).isEqualTo(1);
    }
}
