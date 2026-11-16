package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

class UserInvitationIntegrationTest extends PlatformAdminIntegrationTest {
    private static final String EMAIL = "owner@example.test";

    @Test
    void missingIdentityContractDoesNotReturnATokenAndKeepsTheTenantRetryable() throws Exception {
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
                    .andExpect(status().isFailedDependency())
                    .andReturn().getResponse().getContentAsString();
            JsonNode failure = objectMapper.readTree(body);
            UUID tenantId = UUID.fromString(failure.get("tenantId").asText());
            assertThat(failure.get("invitationStatus").asText()).isEqualTo("RETRY_REQUIRED");
            assertThat(failure.get("seedingApplied").asBoolean()).isTrue();
            assertThat(failure.hasNonNull("adminInviteToken")).isFalse();
            assertThat(body).doesNotContain(EMAIL);
            assertThat(privilegedJdbc().queryForObject("SELECT status FROM tenant_registry WHERE id = ?",
                    String.class, tenantId)).isEqualTo("provisioning");

            mvc.perform(post("/admin/api/v1/tenants/{id}/activate", tenantId)
                            .header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isConflict());
            mvc.perform(post("/admin/api/v1/tenants/{id}/admin-invite", tenantId)
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "retry-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isServiceUnavailable());

            String replay = mvc.perform(post("/admin/api/v1/tenants")
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("invite-group-" + suffix, "invite-tenant-" + suffix, "CASTING", EMAIL)))
                    .andExpect(status().isFailedDependency())
                    .andReturn().getResponse().getContentAsString();
            assertThat(objectMapper.readTree(replay).get("tenantId").asText()).isEqualTo(tenantId.toString());
            assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                    Integer.class, "invite-tenant-" + suffix)).isEqualTo(1);
            String stored = adminJdbc.queryForObject(
                    "SELECT response_body::text FROM idempotency_keys WHERE key = ?", String.class, key);
            assertThat(stored).doesNotContain(EMAIL);
            String combined = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + "\n" + right);
            assertThat(combined).doesNotContain(EMAIL);
        } finally {
            logger.detachAppender(logs);
        }
    }
}
