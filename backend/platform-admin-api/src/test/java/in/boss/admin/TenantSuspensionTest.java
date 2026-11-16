package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class TenantSuspensionTest extends PlatformAdminIntegrationTest {
    @Test
    void suspendsOnlyAnActiveTenantAndRecordsTheReason() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("sus-group-" + suffix, "sus-tenant-" + suffix, "PRECISION_MACHINING", "sus-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());

        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too early\"}"))
                .andExpect(status().isConflict());
        activate(tenantId);
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"billing review\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"repeat\"}"))
                .andExpect(status().isConflict());

        assertThat(privilegedJdbc().queryForObject("SELECT status FROM tenant_registry WHERE id = ?", String.class, tenantId))
                .isEqualTo("suspended");
        String audit = privilegedJdbc().queryForObject(
                "SELECT new_data::text FROM platform_audit_events WHERE entity_id = ? AND action = 'tenant.suspended'",
                String.class, tenantId);
        assertThat(audit).contains("billing review");
    }

    @Test
    void archivedTenantCannotBeSuspended() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("arch-group-" + suffix, "arch-tenant-" + suffix, "CASTING", "arch-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());
        privilegedJdbc().update("UPDATE tenant_registry SET status = 'archived' WHERE id = ?", tenantId);
        mvc.perform(post("/admin/api/v1/tenants/{id}/suspend", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"closed\"}"))
                .andExpect(status().isConflict());
    }
}
