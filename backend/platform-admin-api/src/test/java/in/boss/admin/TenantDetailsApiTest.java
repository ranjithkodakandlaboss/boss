package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class TenantDetailsApiTest extends PlatformAdminIntegrationTest {
    @Test
    void readsAndUpdatesPermittedTenantDetailsWithinTenantContext() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode provisioned = provision("details-group-" + suffix, "details-tenant-" + suffix,
                "FORGING", "details-provision-" + suffix);
        UUID tenantId = UUID.fromString(provisioned.get("tenantId").asText());
        String entityId = provisioned.get("legalEntityId").asText();
        String siteId = provisioned.get("siteId").asText();
        privilegedJdbc().update(
                "INSERT INTO gst_registrations(tenant_id, legal_entity_id, gstin, trade_name, effective_from) " +
                        "VALUES (?, ?, ?, ?, CURRENT_DATE)",
                tenantId, UUID.fromString(entityId), "27ABCDE1234F1Z5", "Acme Trade");

        JsonNode before = getDetails(tenantId);
        assertThat(before.get("tenantName").asText()).isEqualTo("Tenant details-tenant-" + suffix);
        assertThat(before.get("legalEntities").get(0).get("name").asText()).isEqualTo("Example Manufacturing Pvt Ltd");
        assertThat(before.get("legalEntities").get(0).get("pan").asText()).isEqualTo("ABCDE1234F");
        assertThat(before.get("legalEntities").get(0).get("gstRegistrations").get(0).get("gstin").asText())
                .isEqualTo("27ABCDE1234F1Z5");
        assertThat(before.get("sites").get(0).get("name").asText()).isEqualTo("Factory");

        String request = """
                {
                  "tenantName": "Updated customer",
                  "legalEntities": [{
                    "id": "%s",
                    "name": "Updated company display",
                    "legalName": "Updated legal name",
                    "registeredAddress": "10 New Street"
                  }],
                  "sites": [{
                    "id": "%s",
                    "name": "Updated factory",
                    "address": "20 Site Road"
                  }]
                }
                """.formatted(entityId, siteId);
        String response = mvc.perform(patch("/admin/api/v1/tenants/{id}/details", tenantId)
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "details-update-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode updateResult = objectMapper.readTree(response);
        assertThat(updateResult.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(updateResult.get("updatedFields").toString())
                .contains("tenantName", "legalEntity:" + entityId, "site:" + siteId);
        assertThat(response).doesNotContain("ABCDE1234F", "27ABCDE1234F1Z5", "10 New Street");

        JsonNode persisted = getDetails(tenantId);
        assertThat(persisted.get("tenantName").asText()).isEqualTo("Updated customer");
        assertThat(persisted.get("legalEntities").get(0).get("name").asText()).isEqualTo("Updated company display");
        assertThat(persisted.get("legalEntities").get(0).get("legalName").asText()).isEqualTo("Updated legal name");
        assertThat(persisted.get("legalEntities").get(0).get("registeredAddress").asText()).isEqualTo("10 New Street");
        assertThat(persisted.get("sites").get(0).get("name").asText()).isEqualTo("Updated factory");
        assertThat(persisted.get("sites").get(0).get("address").asText()).isEqualTo("20 Site Road");
    }

    @Test
    void rejectsCrossTenantEntityUpdatesAndRollsBackOtherChanges() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JsonNode first = provision("details-first-group-" + suffix, "details-first-" + suffix,
                "FORGING", "details-first-" + suffix);
        JsonNode second = provision("details-second-group-" + suffix, "details-second-" + suffix,
                "CASTING", "details-second-" + suffix);

        mvc.perform(patch("/admin/api/v1/tenants/{id}/details", first.get("tenantId").asText())
                        .header("Authorization", "Bearer admin-token")
                        .header("Idempotency-Key", "details-cross-tenant-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantName": "Must roll back",
                                  "legalEntities": [{
                                    "id": "%s",
                                    "name": "Unauthorized change"
                                  }]
                                }
                                """.formatted(second.get("legalEntityId").asText())))
                .andExpect(status().isNotFound());

        JsonNode unchanged = getDetails(UUID.fromString(first.get("tenantId").asText()));
        assertThat(unchanged.get("tenantName").asText()).isEqualTo("Tenant details-first-" + suffix);
        assertThat(getDetails(UUID.fromString(second.get("tenantId").asText()))
                .get("legalEntities").get(0).get("name").asText()).isEqualTo("Example Manufacturing Pvt Ltd");
    }

    @Test
    void requiresPlatformAdminAuthenticationForTenantDetails() throws Exception {
        mvc.perform(get("/admin/api/v1/tenants/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode getDetails(UUID tenantId) throws Exception {
        String response = mvc.perform(get("/admin/api/v1/tenants/{id}", tenantId)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }
}
