package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantProvisioningTest extends PlatformAdminIntegrationTest {
    @Test
    void provisionsGroupTenantEntitySiteAndIndustrySeedWithoutActivating() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("acme-" + suffix, "plant-" + suffix, "CASTING", "provision-seed-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());
        JdbcTemplate privileged = privilegedJdbc();

        assertThat(provisioned.get("seedingApplied").asBoolean()).isTrue();
        assertThat(privileged.queryForObject("SELECT status FROM tenant_registry WHERE id = ?", String.class, tenantId))
                .isEqualTo("provisioning");
        assertThat(privileged.queryForObject(
                "SELECT initial_admin_email FROM tenant_registry WHERE id = ?", String.class, tenantId)).isNull();
        assertThat(privileged.queryForObject("SELECT count(*) FROM legal_entities WHERE tenant_id = ?", Integer.class, tenantId))
                .isEqualTo(1);
        assertThat(privileged.queryForObject("SELECT count(*) FROM physical_sites WHERE tenant_id = ?", Integer.class, tenantId))
                .isEqualTo(1);
        Integer itemTemplates = privileged.queryForObject(
                "SELECT count(*) FROM industry_template_options WHERE industry_type = 'CASTING' AND category_code = 'ITEM_CATEGORY'",
                Integer.class);
        Integer otherTemplates = privileged.queryForObject(
                "SELECT count(*) FROM industry_template_options WHERE industry_type = 'CASTING' AND category_code <> 'ITEM_CATEGORY'",
                Integer.class);
        assertThat(itemTemplates + otherTemplates).isGreaterThan(0);
        assertThat(privileged.queryForObject("SELECT count(*) FROM item_categories WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(itemTemplates);
        assertThat(privileged.queryForObject("SELECT count(*) FROM tenant_config_options WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(otherTemplates);
        assertThat(privileged.queryForObject(
                "SELECT industry_type FROM tenant_onboarding_config WHERE tenant_id = ?",
                String.class, tenantId)).isEqualTo("CASTING");
        assertThat(privileged.queryForObject("SELECT count(*) FROM units_of_measure WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(16);
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM tenant_config_options", Integer.class)).isZero();
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM item_categories", Integer.class)).isZero();
        String audit = adminJdbc.queryForObject(
                "SELECT string_agg(new_data::text, ',') FROM platform_audit_events WHERE entity_id = ?",
                String.class, tenantId);
        assertThat(audit).doesNotContain("ABCDE1234F").contains("adminEmailPresent");
    }

    @Test
    void derivesCustomerGroupSlugAndRejectsABlankDerivedSlug() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        String body = """
                {"customerGroupName":"Acme Fabricators %s","tenantName":"Acme","tenantSlug":"acme-%s","legalEntityName":"Acme Pvt Ltd","pan":"ABCDE1234F","entityType":"LLP","siteCode":"P1","siteName":"Plant","industryType":"GENERAL_MANUFACTURING"}
                """.formatted(suffix, suffix);
        JsonNode provisioned = objectMapper.readTree(mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "slug-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String groupSlug = privilegedJdbc().queryForObject(
                "SELECT slug FROM customer_groups WHERE id = ?", String.class,
                UUID.fromString(provisioned.get("customerGroupId").asText()));
        assertThat(groupSlug).isEqualTo("acme-fabricators-" + suffix);

        mvc.perform(post("/admin/api/v1/tenants")
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "blank-slug-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerGroupName":"---","tenantName":"Blank","tenantSlug":"blank-%s","legalEntityName":"Blank Pvt Ltd","pan":"ABCDE1234F","entityType":"OTHER","siteCode":"B1","siteName":"Plant","industryType":"FORGING"}
                                """.formatted(suffix)))
                .andExpect(status().isBadRequest());
    }
}
