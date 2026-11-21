package in.boss.inventory;

import in.boss.identity.domain.CompanyLocationService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.masterdata.api.dto.CreateItemCategoryRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.ItemCategoryService;
import in.boss.masterdata.domain.UomService;
import in.boss.production.api.ProductionApiDtos.CreateRoutingTemplateRequest;
import in.boss.production.domain.ProductionJobService;
import in.boss.production.domain.ProductionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@WithMockUser(roles = "OWNER")
class InlineReferenceIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private CompanyLocationService company;

    @Autowired
    private ItemCategoryService categories;

    @Autowired
    private UomService uoms;

    @Autowired
    private ProductionJobService jobs;

    @Autowired
    private ProductionQueryService productionQuery;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void inlineReferencesPersistForTheTenantAndRejectDuplicatesAndOtherRoles() throws Exception {
        UUID tenantId = provisionTenant("inline-ref");
        setTenantContext(tenantId, "OWNER");
        insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Inline Entity', 'Inline Entity Ltd') RETURNING id
                """, tenantId);

        var site = company.addSite("Plant", "PLT", "factory");
        assertThat(site.get("code")).isEqualTo("PLT");
        var store = company.addStore("PLT", "Raw", "RM", "raw_material");
        assertThat(store.get("siteId")).isNotNull();
        assertThatThrownBy(() -> company.addStore("PLT", "Raw again", "RM", "raw_material"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE tenant_id = ? AND code = 'RM'",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_type = 'stores' AND action = 'CREATE'",
                Integer.class, tenantId)).isEqualTo(1);

        var category = categories.createCategory(CreateItemCategoryRequest.builder().code("COND").name("Conductor").build());
        assertThatThrownBy(() -> categories.createCategory(CreateItemCategoryRequest.builder().code("COND").name("Other").build()))
                .isInstanceOf(DuplicateEntityException.class);
        var uom = uoms.createUom(CreateUomRequest.builder().name("Kilogram").symbol("KG-INLINE").uomType("quantity").build());
        assertThatThrownBy(() -> uoms.createUom(CreateUomRequest.builder().name("Kilo").symbol("KG-INLINE").build()))
                .isInstanceOf(DuplicateEntityException.class);

        var template = jobs.createRoutingTemplate(new CreateRoutingTemplateRequest("Frame route", "Cutting"));
        assertThat(template.stageCount()).isEqualTo(1);
        assertThatThrownBy(() -> jobs.createRoutingTemplate(new CreateRoutingTemplateRequest("Frame route", "Welding")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");

        UUID otherTenant = provisionTenant("inline-other");
        setTenantContext(otherTenant, "OWNER");
        insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Other Entity', 'Other Entity Ltd') RETURNING id
                """, otherTenant);
        assertThatThrownBy(() -> company.addStore("PLT", "Foreign", "RM", "raw_material"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("site was not found");
        assertThat(productionQuery.routingTemplates(null)).noneMatch(row -> row.id().equals(template.id()));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM item_categories WHERE tenant_id = ? AND id = ?",
                Integer.class, otherTenant, category.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM units_of_measure WHERE tenant_id = ? AND id = ?",
                Integer.class, otherTenant, uom.getId())).isZero();

        setTenantContext(tenantId, "STORE_KEEPER");
        mockMvc.perform(post("/api/v1/company/stores")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteCode\":\"PLT\",\"name\":\"Extra\",\"code\":\"EX\",\"storeType\":\"raw_material\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/jobs/routing-templates")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"stageName\":\"Cut\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void companyLocationCrudAndListing() {
        UUID tenantId = provisionTenant("location-crud");
        setTenantContext(tenantId, "OWNER");
        insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Location Entity', 'Location Entity Ltd') RETURNING id
                """, tenantId);

        var site = company.addSite("Main Site", "MS1", "factory");
        UUID siteId = (UUID) site.get("id");
        var store = company.addStore("MS1", "Raw Storage", "RS1", "raw_material");
        UUID storeId = (UUID) store.get("id");

        var sites = company.listSites();
        assertThat(sites).isNotEmpty();

        var updatedSite = company.updateSite(siteId, "Updated Site", "MS2", "warehouse", "Street 1", "City A", "State B");
        assertThat(updatedSite.get("name")).isEqualTo("Updated Site");
        assertThat(updatedSite.get("code")).isEqualTo("MS2");

        var updatedStore = company.updateStore(storeId, "Updated Store", "RS2", "consumable");
        assertThat(updatedStore.get("name")).isEqualTo("Updated Store");
        assertThat(updatedStore.get("code")).isEqualTo("RS2");

        company.deleteStore(storeId);
        assertThatThrownBy(() -> company.updateStore(storeId, "Deleted", "DEL", "raw_material"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> company.deleteStore(storeId))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> company.updateSite(UUID.randomUUID(), "Nonexistent", "NE1", "factory", null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }
}
