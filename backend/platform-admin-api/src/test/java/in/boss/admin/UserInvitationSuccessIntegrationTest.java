package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import in.boss.admin.domain.CustomerOwnerInvitePort;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class UserInvitationSuccessIntegrationTest extends PlatformAdminIntegrationTest {
    private static final String EMAIL = "owner@example.test";
    private static final String TOKEN = "one-time-invite-token";

    @MockitoBean CustomerOwnerInvitePort invites;

    @Test
    void provisioningDoesNotCreateInviteAndActivationKeepsTokenOutOfItsResponse() throws Exception {
        authenticateAdmin();
        UUID inviteId = UUID.randomUUID();
        when(invites.inviteInitialOwner(any(), eq(EMAIL))).thenReturn(
                new CustomerOwnerInvitePort.OwnerInvite(inviteId, TOKEN, Instant.parse("2026-12-01T00:00:00Z")));
        Logger logger = (Logger) LoggerFactory.getLogger("in.boss.admin");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        String suffix = UUID.randomUUID().toString();
        String key = "created-" + suffix;
        try {
            String body = mvc.perform(post("/admin/api/v1/tenants")
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("created-group-" + suffix, "created-tenant-" + suffix, "FORGING", EMAIL)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            JsonNode created = objectMapper.readTree(body);
            UUID tenantId = UUID.fromString(created.get("tenantId").asText());
            assertThat(created.get("invitationStatus").asText()).isEqualTo("PENDING_ACTIVATION");
            assertThat(created.hasNonNull("adminInviteToken")).isFalse();
            verify(invites, never()).inviteInitialOwner(any(), any());

            String detailsBody = mvc.perform(get("/admin/api/v1/tenants/{id}", tenantId)
                            .header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode details = objectMapper.readTree(detailsBody);
            assertThat(details.get("adminEmail").asText()).isEqualTo(EMAIL);
            assertThat(details.get("tenantStatus").asText()).isEqualTo("provisioning");
            assertThat(details.get("legalEntities")).hasSize(1);
            assertThat(details.get("sites").get(0).get("code").asText()).isEqualTo("SITE-01");
            assertThat(detailsBody).doesNotContain(TOKEN);

            String updateResultBody = mvc.perform(patch("/admin/api/v1/tenants/{id}/details", tenantId)
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "details-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "tenantName": "Updated before activation",
                                      "legalEntities": [{
                                        "id": "%s",
                                        "registeredAddress": "10 New Street"
                                      }]
                                    }
                                    """.formatted(created.get("legalEntityId").asText())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode updateResult = objectMapper.readTree(updateResultBody);
            assertThat(updateResult.get("tenantId").asText()).isEqualTo(tenantId.toString());
            assertThat(updateResult.get("updatedFields").toString()).contains("tenantName", "legalEntity:");
            assertThat(updateResultBody).doesNotContain(EMAIL, TOKEN, "10 New Street");
            JsonNode updatedDetails = objectMapper.readTree(mvc.perform(get("/admin/api/v1/tenants/{id}", tenantId)
                            .header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(updatedDetails.get("tenantName").asText()).isEqualTo("Updated before activation");
            assertThat(updatedDetails.get("legalEntities").get(0).get("registeredAddress").asText())
                    .isEqualTo("10 New Street");
            verify(invites, never()).inviteInitialOwner(any(), any());

            String replay = mvc.perform(post("/admin/api/v1/tenants")
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("created-group-" + suffix, "created-tenant-" + suffix, "FORGING", EMAIL)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-Idempotent-Replayed", "true"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(replay).doesNotContain(TOKEN).doesNotContain(EMAIL);
            String stored = adminJdbc.queryForObject(
                    "SELECT coalesce(string_agg(response_body::text, ','), '') FROM idempotency_keys", String.class);
            String audit = adminJdbc.queryForObject(
                    "SELECT coalesce(string_agg(new_data::text, ','), '') FROM platform_audit_events WHERE entity_id = ?",
                    String.class, tenantId);
            assertThat(stored).doesNotContain(TOKEN).doesNotContain(EMAIL);
            assertThat(audit).doesNotContain(TOKEN).doesNotContain(EMAIL);
            String combined = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(combined).doesNotContain(TOKEN).doesNotContain(EMAIL);

            String activation = mvc.perform(post("/admin/api/v1/tenants/{id}/activate", tenantId)
                            .header("Authorization", "Bearer admin-token"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(activation).doesNotContain(TOKEN).doesNotContain(EMAIL);
            assertThat(objectMapper.readTree(activation).get("invitationStatus").asText()).isEqualTo("CREATED");
            verify(invites).inviteInitialOwner(any(), eq(EMAIL));
            mvc.perform(post("/admin/api/v1/tenants/{id}/admin-invite", tenantId)
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "again-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + EMAIL + "\"}"))
                    .andExpect(status().isConflict());
            assertThat(adminJdbc.queryForObject(
                    "SELECT coalesce(string_agg(response_body::text, ','), '') FROM idempotency_keys", String.class))
                    .doesNotContain(TOKEN);
        } finally {
            logger.detachAppender(logs);
        }
    }
}
