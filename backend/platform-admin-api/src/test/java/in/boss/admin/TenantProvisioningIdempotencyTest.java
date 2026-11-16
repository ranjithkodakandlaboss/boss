package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantProvisioningIdempotencyTest extends PlatformAdminIntegrationTest {
    @Test
    void sameKeyAndPayloadReplaysTheOriginalTenant() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        String key = "idem-" + suffix;
        JsonNode first = provision("idem-group-" + suffix, "idem-tenant-" + suffix, "CASTING", key);
        String replay = mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("idem-group-" + suffix, "idem-tenant-" + suffix, "CASTING")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).get("tenantId").asText()).isEqualTo(first.get("tenantId").asText());
        assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "idem-tenant-" + suffix)).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentPayloadDoesNotCreateAnotherTenant() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        String key = "mismatch-" + suffix;
        provision("match-group-" + suffix, "match-tenant-" + suffix, "FORGING", key);
        mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("other-group-" + suffix, "other-tenant-" + suffix, "CASTING")))
                .andExpect(status().isConflict());
        JdbcTemplate privileged = privilegedJdbc();
        assertThat(privileged.queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "other-tenant-" + suffix)).isZero();
        assertThat(privileged.queryForObject("SELECT count(*) FROM customer_groups WHERE slug = ?",
                Integer.class, "other-group-" + suffix)).isZero();
    }

    @Test
    void inFlightKeyReturnsRetryAndDoesNotCreateAnotherTenant() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        String key = "locked-" + suffix;
        provision("locked-group-" + suffix, "locked-tenant-" + suffix, "CASTING", key);
        privilegedJdbc().update(
                "UPDATE idempotency_keys SET lock_status = 'locked', expires_at = NOW() + INTERVAL '1 hour' WHERE key = ?",
                key);
        mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("locked-group-" + suffix, "locked-tenant-" + suffix, "CASTING")))
                .andExpect(status().isServiceUnavailable());
        assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "locked-tenant-" + suffix)).isEqualTo(1);
    }

    @Test
    void expiredKeyIsReclaimedAndDoesNotReplayThePreviousTenant() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        String key = "expired-" + suffix;
        JsonNode first = provision("expired-group-" + suffix, "expired-tenant-" + suffix, "CASTING", key);
        privilegedJdbc().update(
                "UPDATE idempotency_keys SET expires_at = NOW() - INTERVAL '1 hour' WHERE key = ?", key);
        JsonNode second = provision("reclaimed-group-" + suffix, "reclaimed-tenant-" + suffix, "FORGING", key);
        assertThat(second.get("tenantId").asText()).isNotEqualTo(first.get("tenantId").asText());
        assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "reclaimed-tenant-" + suffix)).isEqualTo(1);
    }

    @Test
    void missingOrOversizedKeyIsRejectedBeforeATenantIsCreated() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("missing-key-" + suffix, "missing-tenant-" + suffix, "CASTING")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "k".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("long-key-" + suffix, "long-tenant-" + suffix, "CASTING")))
                .andExpect(status().isBadRequest());
        assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "missing-tenant-" + suffix)).isZero();
        assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "long-tenant-" + suffix)).isZero();
    }
}
