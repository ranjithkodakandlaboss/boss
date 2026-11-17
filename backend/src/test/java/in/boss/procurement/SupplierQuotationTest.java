package in.boss.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrRequest;
import in.boss.procurement.api.ProcurementApiDtos.PurchaseRequisitionResponse;
import in.boss.procurement.domain.PurchaseRequisitionService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class SupplierQuotationTest extends BaseMasterDataIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PurchaseRequisitionService prService;

    private UUID tenantId;
    private UUID actorId;
    private UUID siteId;
    private UUID supplierId;
    private UUID secondSupplierId;
    private UUID itemId;
    private UUID uomId;
    private UUID prId;
    private UUID prLineId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("supplier-quotation-test");
        actorId = UUID.randomUUID();
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Quotation Entity', 'Quotation Entity Ltd') RETURNING id::text
                """, tenantId);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Quotation Site', ?) RETURNING id::text
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 8));
        setContext(tenantId, siteId, actorId);
        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Each', ?, TRUE) RETURNING id::text
                """, tenantId, "EA-" + UUID.randomUUID().toString().substring(0, 6));
        itemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Quotation Item', 'raw_material', ?) RETURNING id::text
                """, tenantId, "ITEM-" + UUID.randomUUID().toString().substring(0, 8), uomId);
        supplierId = insert("""
                INSERT INTO suppliers (tenant_id, code, name)
                VALUES (?, ?, 'Quotation Supplier') RETURNING id::text
                """, tenantId, "SUP-" + UUID.randomUUID().toString().substring(0, 8));
        secondSupplierId = insert("""
                INSERT INTO suppliers (tenant_id, code, name)
                VALUES (?, ?, 'Second Quotation Supplier') RETURNING id::text
                """, tenantId, "SUP-" + UUID.randomUUID().toString().substring(0, 8));

        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                java.util.List.of(new CreatePrLineRequest(
                        itemId, BigDecimal.TEN, null, uomId, null, null, null, null, null)), null));
        prService.submitPr(pr.id());
        prService.approvePr(pr.id());
        prId = pr.id();
        prLineId = pr.lines().get(0).id();
    }

    @Test
    void recordsReviewsSelectsAndComparesQuotationOffers() throws Exception {
        UUID quotationId = createQuotation(UUID.randomUUID().toString());
        UUID secondQuotationId = createQuotationFor(secondSupplierId, UUID.randomUUID().toString());
        assertThat(readQuotation(quotationId).path("status").asText()).isEqualTo("requested");

        postJson("/api/v1/supplier-quotations/" + quotationId + "/receive",
                "{\"validUntil\":\"2030-01-01\",\"lines\":[{\"prLineId\":\"" + prLineId
                        + "\",\"unitPrice\":12.50,\"leadTimeDays\":4}]}",
                UUID.randomUUID().toString()).andExpect(status().isOk());
        postEmpty("/api/v1/supplier-quotations/" + quotationId + "/review",
                UUID.randomUUID().toString()).andExpect(status().isOk());
        postEmpty("/api/v1/supplier-quotations/" + quotationId + "/select",
                UUID.randomUUID().toString()).andExpect(status().isOk());
        postJson("/api/v1/supplier-quotations/" + secondQuotationId + "/receive",
                "{\"lines\":[{\"prLineId\":\"" + prLineId
                        + "\",\"unitPrice\":11.25,\"leadTimeDays\":2}]}",
                UUID.randomUUID().toString()).andExpect(status().isOk());
        postEmpty("/api/v1/supplier-quotations/" + secondQuotationId + "/review",
                UUID.randomUUID().toString()).andExpect(status().isOk());
        postEmpty("/api/v1/supplier-quotations/" + secondQuotationId + "/reject",
                UUID.randomUUID().toString()).andExpect(status().isOk());

        JsonNode selected = readQuotation(quotationId);
        assertThat(selected.path("status").asText()).isEqualTo("selected");
        assertThat(selected.path("lines").get(0).path("unitPrice").decimalValue())
                .isEqualByComparingTo("12.50");
        assertThat(auditCount("supplier_quotations", quotationId)).isEqualTo(4);

        setContext(tenantId, siteId, actorId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pr_lines pl
                JOIN items i ON i.id = pl.item_id AND i.tenant_id = pl.tenant_id
                JOIN units_of_measure u ON u.id = pl.uom_id AND u.tenant_id = pl.tenant_id
                WHERE pl.tenant_id = ? AND pl.pr_id = ?
                """, Long.class, tenantId, prId)).isEqualTo(1L);
        MvcResult comparison = mockMvc.perform(get("/api/v1/prs/" + prId + "/supplier-quotations/comparison")
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode offers = objectMapper.readTree(comparison.getResponse().getContentAsString())
                .path("lines").get(0).path("offers");
        assertThat(offers).hasSize(2);
        assertThat(offers.toString()).contains(quotationId.toString(), secondQuotationId.toString());

        setContext(tenantId, siteId, actorId);
        MvcResult listed = mockMvc.perform(get("/api/v1/prs/" + prId + "/supplier-quotations")
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode quotationPage = objectMapper.readTree(listed.getResponse().getContentAsString());
        assertThat(quotationPage.path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidTransitionsAndQuotationLinesFromAnotherPr() throws Exception {
        UUID quotationId = createQuotation(UUID.randomUUID().toString());
        postEmpty("/api/v1/supplier-quotations/" + quotationId + "/review",
                UUID.randomUUID().toString()).andExpect(status().isConflict());
        UUID unrelatedPrLineId = UUID.randomUUID();
        postJson("/api/v1/supplier-quotations/" + quotationId + "/receive",
                "{\"lines\":[{\"prLineId\":\"" + unrelatedPrLineId + "\",\"unitPrice\":12.50}]}",
                UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        assertThat(readQuotation(quotationId).path("status").asText()).isEqualTo("requested");
    }

    @Test
    void createIsIdempotentAndTenantAndRoleScoped() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID quotationId = createQuotation(key);

        setContext(tenantId, siteId, actorId);
        MvcResult replay = mockMvc.perform(post("/api/v1/prs/" + prId + "/supplier-quotations")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).path("id").asText())
                .isEqualTo(quotationId.toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_quotations WHERE tenant_id = ? AND pr_id = ?
                """, Long.class, tenantId, prId)).isEqualTo(1L);

        UUID otherTenant = provisionTenant("supplier-quotation-other");
        setContext(otherTenant, null, UUID.randomUUID());
        mockMvc.perform(get("/api/v1/supplier-quotations/" + quotationId)
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound());

        setContext(tenantId, siteId, actorId);
        mockMvc.perform(post("/api/v1/prs/" + prId + "/supplier-quotations")
                        .with(user("store").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\"}"))
                .andExpect(status().isForbidden());
    }

    private UUID createQuotation(String key) throws Exception {
        return createQuotationFor(supplierId, key);
    }

    private UUID createQuotationFor(UUID supplier, String key) throws Exception {
        setContext(tenantId, siteId, actorId);
        MvcResult result = mockMvc.perform(post("/api/v1/prs/" + prId + "/supplier-quotations")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplier + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private JsonNode readQuotation(UUID quotationId) throws Exception {
        setContext(tenantId, siteId, actorId);
        MvcResult result = mockMvc.perform(get("/api/v1/supplier-quotations/" + quotationId)
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body, String key)
            throws Exception {
        setContext(tenantId, siteId, actorId);
        return mockMvc.perform(post(path)
                .with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions postEmpty(String path, String key)
            throws Exception {
        setContext(tenantId, siteId, actorId);
        return mockMvc.perform(post(path)
                .with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private void setContext(UUID tenant, UUID site, UUID actor) {
        BossContextHolder.set(new BossContext(tenant, actor, Set.of("OWNER"), site, null));
    }

    private long auditCount(String entityType, UUID entityId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE entity_type = ? AND entity_id = ?
                """, Long.class, entityType, entityId);
    }

    private UUID insert(String sql, Object... args) {
        return UUID.fromString(jdbc.queryForObject(sql, String.class, args));
    }
}
