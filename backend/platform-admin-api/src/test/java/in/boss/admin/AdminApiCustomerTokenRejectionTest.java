package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import in.boss.admin.security.AdminTokenVerifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminApiCustomerTokenRejectionTest extends PlatformAdminIntegrationTest {
    @Test
    void rejectsCustomerFirebaseTokensAndAcceptsOnlyRegisteredActivePlatformAdmins() throws Exception {
        when(verifier.verify("customer-token")).thenThrow(
                new in.boss.admin.security.FirebaseAdminTokenVerifier.CustomerProjectTokenException());
        when(verifier.verify("unknown-admin-token"))
                .thenReturn(new AdminTokenVerifier.VerifiedAdminToken("unknown-uid", "unknown@example.test"));

        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/api/v1/tenants").header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/suspend")
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/reactivate")
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/activate")
                        .header("Authorization", "Bearer customer-token"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/admin/api/v1/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/reactivate"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/reactivate")
                        .header("Authorization", "Bearer unknown-admin-token"))
                .andExpect(status().isUnauthorized());

        authenticateAdmin();
        mvc.perform(post("/admin/api/v1/tenants/00000000-0000-0000-0000-000000000099/reactivate")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void provisionsAgainstTheMigratedSchemaAndWritesAppendOnlyAuditWithoutBusinessTableAccess() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("group-" + suffix, "tenant-" + suffix, "FABRICATION_STEEL", "provision-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());

        assertThat(provisioned.get("seedingApplied").asBoolean()).isTrue();
        assertThat(provisioned.get("invitationStatus").asText()).isEqualTo("NOT_REQUESTED");
        assertThat(provisioned.hasNonNull("adminInviteToken")).isFalse();
        assertThat(privilegedJdbc().queryForObject(
                "SELECT count(*) FROM platform_audit_events WHERE entity_id = ? AND action = 'tenant.provisioned'",
                Integer.class, tenantId)).isEqualTo(1);
        assertThatThrownBy(() -> adminJdbc.queryForObject("SELECT count(*) FROM items", Integer.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> privilegedJdbc().update(
                "UPDATE platform_audit_events SET action = 'tampered' WHERE entity_id = ?", tenantId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void duplicateGroupOrTenantSlugsReturnConflictAndDoNotLeavePartialGroupRows() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        provision("group-a-" + suffix, "tenant-a-" + suffix, "FORGING", "key-a-" + suffix);

        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "key-duplicate-group-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("group-a-" + suffix, "tenant-b-" + suffix, "CASTING")))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "key-duplicate-tenant-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("group-b-" + suffix, "tenant-a-" + suffix, "CASTING")))
                .andExpect(status().isConflict());

        assertThat(privilegedJdbc().queryForObject("SELECT count(*) FROM customer_groups WHERE slug = ?",
                Integer.class, "group-b-" + suffix)).isZero();
    }

    @Test
    void auditFailureRollsBackGroupTenantEntitySiteAndOnboardingRows() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JdbcTemplate privileged = privilegedJdbc();
        int entitiesBefore = privileged.queryForObject("SELECT count(*) FROM legal_entities", Integer.class);
        int sitesBefore = privileged.queryForObject("SELECT count(*) FROM physical_sites", Integer.class);
        int auditsBefore = privileged.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class);
        privileged.execute("""
                CREATE OR REPLACE FUNCTION test_fail_tenant_provision_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.action = 'tenant.provisioned' THEN
                    RAISE EXCEPTION 'forced audit write failure';
                  END IF;
                  RETURN NEW;
                END $$
                """);
        privileged.execute("CREATE TRIGGER test_fail_tenant_provision_audit BEFORE INSERT ON platform_audit_events " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_tenant_provision_audit()");
        try {
            assertThatThrownBy(() -> mvc.perform(post("/admin/api/v1/tenants").header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "rollback-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("group-rollback-" + suffix, "tenant-rollback-" + suffix,
                                    "ELECTRONICS_ASSEMBLY"))))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
        } finally {
            privileged.execute("DROP TRIGGER test_fail_tenant_provision_audit ON platform_audit_events");
            privileged.execute("DROP FUNCTION test_fail_tenant_provision_audit()");
        }

        assertThat(privileged.queryForObject("SELECT count(*) FROM customer_groups WHERE slug = ?",
                Integer.class, "group-rollback-" + suffix)).isZero();
        assertThat(privileged.queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "tenant-rollback-" + suffix)).isZero();
        assertThat(privileged.queryForObject("SELECT count(*) FROM legal_entities", Integer.class)).isEqualTo(entitiesBefore);
        assertThat(privileged.queryForObject("SELECT count(*) FROM physical_sites", Integer.class)).isEqualTo(sitesBefore);
        assertThat(privileged.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class)).isEqualTo(auditsBefore);
    }
}
