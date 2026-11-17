package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import in.boss.admin.email.ResendEmailSender;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class UserInvitationIntegrationTest extends PlatformAdminIntegrationTest {
    private static final String EMAIL = "owner@example.test";

    @MockitoBean
    ResendEmailSender emailSender;

    @BeforeEach
    void deliverInvitationEmail() {
        when(emailSender.sendInvitationEmail(any(), any(), any(), any(), any())).thenReturn(
                new ResendEmailSender.EmailSendResult(true, "test-message", null));
    }

    @Test
    void provisioningStoresAdminContactAndOnlyActivationSendsInvitation() throws Exception {
        authenticateAdmin();
        Logger logger = (Logger) LoggerFactory.getLogger("in.boss.admin");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        String suffix = UUID.randomUUID().toString();
        String key = "invite-" + suffix;
        try {
            String body = mvc.perform(post("/admin/api/v1/tenants")
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("invite-group-" + suffix, "invite-tenant-" + suffix, "CASTING", EMAIL)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            JsonNode created = objectMapper.readTree(body);
            UUID tenantId = UUID.fromString(created.get("tenantId").asText());
            assertThat(created.get("invitationStatus").asText()).isEqualTo("PENDING_ACTIVATION");
            assertThat(created.hasNonNull("adminInviteToken")).isFalse();
            assertThat(body).doesNotContain(EMAIL);
            assertThat(privilegedJdbc().queryForObject(
                    "SELECT initial_admin_email FROM tenant_registry WHERE id = ?", String.class, tenantId))
                    .isEqualTo(EMAIL);
            assertThat(privilegedJdbc().queryForObject(
                    "SELECT count(*) FROM invite_tokens WHERE tenant_id = ?", Integer.class, tenantId)).isZero();
            verify(emailSender, never()).sendInvitationEmail(any(), any(), any(), any(), any());

            String replay = mvc.perform(post("/admin/api/v1/tenants")
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("invite-group-" + suffix, "invite-tenant-" + suffix, "CASTING", EMAIL)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(replay).doesNotContain("adminInviteToken");
            String stored = adminJdbc.queryForObject(
                    "SELECT response_body::text FROM idempotency_keys WHERE key = ?", String.class, key);
            assertThat(stored).doesNotContain(EMAIL).doesNotContain("adminInviteToken");
            String combined = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + "\n" + right);
            assertThat(combined).doesNotContain(EMAIL);

            String activated = mvc.perform(post("/admin/api/v1/tenants/{id}/activate", tenantId)
                            .header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantStatus").value("active"))
                    .andExpect(jsonPath("$.invitationStatus").value("CREATED"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(activated).doesNotContain(EMAIL).doesNotContain("adminInviteToken");
            verify(emailSender).sendInvitationEmail(eq(EMAIL), any(), any(), any(), any());
            assertThat(privilegedJdbc().queryForObject(
                    "SELECT count(*) FROM invite_tokens WHERE tenant_id = ? AND status = 'pending'",
                    Integer.class, tenantId)).isEqualTo(1);

            mvc.perform(post("/admin/api/v1/tenants/{id}/admin-invite", tenantId)
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "retry-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(post("/admin/api/v1/tenants/{id}/activate", tenantId)
                            .header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isConflict());
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void failedEmailDeliveryLeavesTenantActiveAndCanBeRetriedWithoutDuplicatePendingInvites() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode created = objectMapper.readTree(mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "failed-delivery-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("failed-group-" + suffix, "failed-tenant-" + suffix, "CASTING", EMAIL)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        UUID tenantId = UUID.fromString(created.get("tenantId").asText());
        when(emailSender.sendInvitationEmail(any(), any(), any(), any(), any()))
                .thenReturn(new ResendEmailSender.EmailSendResult(false, null, "provider unavailable"));

        mvc.perform(post("/admin/api/v1/tenants/{id}/activate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantStatus").value("active"))
                .andExpect(jsonPath("$.invitationStatus").value("RETRY_REQUIRED"));
        assertThat(privilegedJdbc().queryForObject(
                "SELECT status FROM tenant_registry WHERE id = ?", String.class, tenantId)).isEqualTo("active");
        assertThat(adminJdbc.queryForObject(
                "SELECT new_data->>'invitationStatus' FROM platform_audit_events " +
                        "WHERE entity_id = ? AND action = 'tenant.admin_invite_retry_required' " +
                        "ORDER BY created_at DESC LIMIT 1",
                String.class, tenantId)).isEqualTo("RETRY_REQUIRED");

        when(emailSender.sendInvitationEmail(any(), any(), any(), any(), any()))
                .thenReturn(new ResendEmailSender.EmailSendResult(true, "retry-message", null));
        var retry = mvc.perform(post("/admin/api/v1/tenants/{id}/admin-invite", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "retry-delivery-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andReturn().getResponse();
        assertThat(retry.getStatus()).as(retry.getContentAsString()).isEqualTo(201);

        assertThat(privilegedJdbc().queryForObject(
                "SELECT count(*) FROM invite_tokens WHERE tenant_id = ? AND role = 'OWNER' AND status = 'pending'",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(privilegedJdbc().queryForObject(
                "SELECT count(*) FROM invite_tokens WHERE tenant_id = ? AND role = 'OWNER' AND status = 'cancelled'",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    void activationWithoutAdministratorEmailDoesNotAttemptInvitationDelivery() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode created = provision("no-admin-group-" + suffix, "no-admin-tenant-" + suffix,
                "FORGING", "no-admin-" + suffix);
        UUID tenantId = UUID.fromString(created.get("tenantId").asText());

        mvc.perform(post("/admin/api/v1/tenants/{id}/activate", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantStatus").value("active"))
                .andExpect(jsonPath("$.invitationStatus").value("NOT_REQUESTED"));
        verify(emailSender, never()).sendInvitationEmail(any(), any(), any(), any(), any());
    }
}
