package in.boss.admin;

import in.boss.admin.domain.CustomerOwnerInvitePort;
import in.boss.admin.email.ResendEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerActivationControllerTest extends PlatformAdminIntegrationTest {

    @Autowired
    private CustomerOwnerInvitePort invitePort;

    @MockitoBean
    private ResendEmailSender emailSender;

    @Test
    void testValidateAndActivateCustomerAccount() throws Exception {
        authenticateAdmin();
        when(emailSender.sendInvitationEmail(any(), any(), any(), any(), any()))
                .thenReturn(new ResendEmailSender.EmailSendResult(true, "msg-12345", null));

        String suffix = UUID.randomUUID().toString();
        var provisioned = provision("activation-group-" + suffix, "activation-tenant-" + suffix, "FORGING", "activation-admin-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());

        // Create an invite for this tenant
        String email = "testowner." + suffix + "@example.com";
        CustomerOwnerInvitePort.OwnerInvite invite = invitePort.inviteInitialOwner(tenantId, email);

        // 1. Validate token via GET /admin/api/v1/invites/validate
        mvc.perform(get("/admin/api/v1/invites/validate")
                        .param("token", invite.oneTimeToken())
                        .param("tenant", "activation-tenant-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.tenantSlug").value("activation-tenant-" + suffix))
                .andExpect(jsonPath("$.status").value("pending"));

        // 2. Cross-tenant isolation check: if another tenant slug is requested, reject with 403
        mvc.perform(get("/admin/api/v1/invites/validate")
                        .param("token", invite.oneTimeToken())
                        .param("tenant", "other-tenant-slug"))
                .andExpect(status().isForbidden());

        // 3. Activate account via POST /admin/api/v1/invites/activate
        String firebaseUid = "fb-uid-" + suffix;
        String activationBody = """
                {
                    "token": "%s",
                    "firebaseUid": "%s",
                    "displayName": "Test Admin",
                    "phone": "+919876543210",
                    "tenantSlug": "activation-tenant-%s"
                }
                """.formatted(invite.oneTimeToken(), firebaseUid, suffix);

        mvc.perform(post("/admin/api/v1/invites/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activationBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.tenantSlug").value("activation-tenant-" + suffix))
                .andExpect(jsonPath("$.status").value("active"));

        // 4. Validate again should fail with CONFLICT (already accepted)
        mvc.perform(get("/admin/api/v1/invites/validate")
                        .param("token", invite.oneTimeToken())
                        .param("tenant", "activation-tenant-" + suffix))
                .andExpect(status().isConflict());

        // 5. Verify tenant_registry is now 'active'
        String tenantStatus = privilegedJdbc().queryForObject(
                "SELECT status FROM tenant_registry WHERE id = ?", String.class, tenantId);
        assertThat(tenantStatus).isEqualTo("active");
    }
}
