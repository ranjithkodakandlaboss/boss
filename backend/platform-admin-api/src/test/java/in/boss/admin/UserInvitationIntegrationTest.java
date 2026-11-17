package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void provisioningCreatesOneOwnerInviteAndDoesNotPersistTheToken() throws Exception {
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
            String token = created.get("adminInviteToken").asText();
            assertThat(created.get("invitationStatus").asText()).isEqualTo("CREATED");
            assertThat(token).isNotBlank();
            assertThat(body).doesNotContain(EMAIL);
            assertThat(privilegedJdbc().queryForObject(
                    "SELECT role FROM invite_tokens WHERE tenant_id = ?", String.class, tenantId)).isEqualTo("OWNER");
            assertThat(privilegedJdbc().queryForObject(
                    "SELECT token_hash = ? FROM invite_tokens WHERE tenant_id = ?", Boolean.class, token, tenantId)).isFalse();

            String replay = mvc.perform(post("/admin/api/v1/tenants")
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("invite-group-" + suffix, "invite-tenant-" + suffix, "CASTING", EMAIL)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(replay).doesNotContain(token);
            String stored = adminJdbc.queryForObject(
                    "SELECT response_body::text FROM idempotency_keys WHERE key = ?", String.class, key);
            assertThat(stored).doesNotContain(token).doesNotContain(EMAIL);
            String combined = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + "\n" + right);
            assertThat(combined).doesNotContain(token).doesNotContain(EMAIL);

            mvc.perform(post("/admin/api/v1/tenants/{id}/admin-invite", tenantId)
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "retry-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isConflict());
            activate(tenantId);
        } finally {
            logger.detachAppender(logs);
        }
    }
}
