package in.boss.admin;

import in.boss.admin.activation.CustomerActivationController;
import in.boss.admin.api.TenantSummary;
import in.boss.admin.domain.CustomerOwnerInvitePort;
import in.boss.admin.domain.TenantManagementService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TenantSlugResolutionTest extends PlatformAdminIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantManagementService tenantManagementService;

    @MockBean
    private CustomerOwnerInvitePort invites;

    @Test
    void provisionsWithSlugAndResolvesTenantPublicly() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String groupSlug = "slug-group-" + suffix;
        String tenantSlug = "slug-tenant-" + suffix;

        when(invites.inviteInitialOwner(any(), eq("admin@slugtest.test"))).thenReturn(
                new CustomerOwnerInvitePort.OwnerInvite(UUID.randomUUID(), "test-token", Instant.parse("2026-12-01T00:00:00Z")));

        String requestBody = """
                {
                  "customerGroupName": "Slug Test Group %s",
                  "customerGroupSlug": "%s",
                  "tenantName": "Slug Test Tenant %s",
                  "tenantSlug": "%s",
                  "legalEntityName": "Slug Test Legal Entity",
                  "pan": "ABCDE1234F",
                  "entityType": "PRIVATE_LIMITED",
                  "siteCode": "SITE-SLUG",
                  "siteName": "Slug Site",
                  "industryType": "FORGING",
                  "adminEmail": "admin@slugtest.test"
                }
                """.formatted(suffix, groupSlug, suffix, tenantSlug);

        // 1. Provision customer
        mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "slug-idem-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitationStatus").value("CREATED"));

        // 2. Resolve tenant by slug without authentication (public access for routing)
        mvc.perform(get("/admin/api/v1/tenants/resolve").param("slug", tenantSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(tenantSlug))
                .andExpect(jsonPath("$.workspaceUrl").isNotEmpty());

        // 3. Resolve nonexistent slug returns 404
        mvc.perform(get("/admin/api/v1/tenants/resolve").param("slug", "non-existent-slug-" + suffix))
                .andExpect(status().isNotFound());

        // 4. Duplicate slug provisioning attempt returns 409 Conflict
        mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "slug-idem-conflict-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());
    }
}
