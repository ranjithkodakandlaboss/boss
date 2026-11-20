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
class SpoCrudTest extends BaseMasterDataIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PurchaseRequisitionService prService;

    private UUID tenantId;
    private UUID actorId;
    private UUID siteId;
    private UUID supplierId;
    private UUID uomId;
    private UUID itemId;
    private UUID prId;
    private UUID prLineId;
    private UUID quotationId;
    private UUID otherPrQuotationId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("spo-crud-test");
        actorId = UUID.randomUUID();
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'SPO Entity', 'SPO Entity Ltd') RETURNING id::text
                """, tenantId);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'SPO Site', ?) RETURNING id::text
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 8));
        setContext(tenantId, siteId, actorId);
        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Each', ?, TRUE) RETURNING id::text
                """, tenantId, "EA-" + UUID.randomUUID().toString().substring(0, 6));
        itemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'SPO Item', 'raw_material', ?) RETURNING id::text
                """, tenantId, "ITEM-" + UUID.randomUUID().toString().substring(0, 8), uomId);
        supplierId = insert("""
                INSERT INTO suppliers (tenant_id, code, name)
                VALUES (?, ?, 'SPO Supplier') RETURNING id::text
                """, tenantId, "SUP-" + UUID.randomUUID().toString().substring(0, 8));
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                java.util.List.of(new CreatePrLineRequest(
                        itemId, BigDecimal.TEN, null, uomId, null, null, null, null, null)), null));
        prService.submitPr(pr.id());
        prService.approvePr(pr.id());
        prId = pr.id();
        prLineId = pr.lines().get(0).id();
        quotationId = insert("""
                INSERT INTO supplier_quotations
                  (tenant_id, sq_number, pr_id, supplier_id, status, created_by)
                VALUES (?, ?, ?, ?, 'selected', ?) RETURNING id::text
                """, tenantId, "SQ-" + UUID.randomUUID(), prId, supplierId, actorId);
        insert("""
                INSERT INTO supplier_quotation_lines
                  (tenant_id, sq_id, pr_line_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, ?, 1, ?, 10, ?, 12.50) RETURNING id::text
                """, tenantId, quotationId, prLineId, itemId, uomId);

        PurchaseRequisitionResponse otherPr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                java.util.List.of(new CreatePrLineRequest(
                        itemId, BigDecimal.valueOf(2), null, uomId, null, null, null, null, null)), null));
        prService.submitPr(otherPr.id());
        prService.approvePr(otherPr.id());
        otherPrQuotationId = insert("""
                INSERT INTO supplier_quotations
                  (tenant_id, sq_number, pr_id, supplier_id, status, created_by)
                VALUES (?, ?, ?, ?, 'selected', ?) RETURNING id::text
                """, tenantId, "SQ-" + UUID.randomUUID(), otherPr.id(), supplierId, actorId);
    }

    @Test
    void createsFromSelectedQuotationAndEnforcesStatusTransitions() throws Exception {
        UUID spoId = createSpo(UUID.randomUUID().toString(), supplierId, quotationId, "5.000", "12.50");
        JsonNode created = readSpo(spoId);
        assertThat(created.path("status").asText()).isEqualTo("draft");
        assertThat(created.path("supplierQuotationId").asText()).isEqualTo(quotationId.toString());
        assertThat(created.path("prId").asText()).isEqualTo(prId.toString());
        assertThat(created.path("totalAmount").decimalValue()).isEqualByComparingTo("62.50");

        setContext(tenantId, siteId, actorId);
        MvcResult listed = mockMvc.perform(get("/api/v1/spos")
                        .with(user("owner").roles("OWNER"))
                        .param("supplierId", supplierId.toString())
                        .param("status", "draft"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(listed.getResponse().getContentAsString())
                .path("content").size()).isEqualTo(1);

        postEmpty("/api/v1/spos/" + spoId + "/acknowledge", UUID.randomUUID().toString())
                .andExpect(status().isConflict());
        postEmpty("/api/v1/spos/" + spoId + "/issue", UUID.randomUUID().toString())
                .andExpect(status().isOk());
        assertThat(readSpo(spoId).path("status").asText()).isEqualTo("issued");
        postEmpty("/api/v1/spos/" + spoId + "/acknowledge", UUID.randomUUID().toString())
                .andExpect(status().isOk());
        assertThat(readSpo(spoId).path("status").asText()).isEqualTo("acknowledged");
        setContext(tenantId, siteId, actorId);
        jdbc.update("UPDATE spos SET status = 'partially_received' WHERE tenant_id = ? AND id = ?",
                tenantId, spoId);
        jdbc.update("UPDATE spo_lines SET received_qty = 2 WHERE tenant_id = ? AND spo_id = ?",
                tenantId, spoId);
        postEmpty("/api/v1/spos/" + spoId + "/cancel", UUID.randomUUID().toString())
                .andExpect(status().isOk());
        assertThat(readSpo(spoId).path("status").asText()).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM purchase_requisitions WHERE tenant_id = ? AND id = ?
                """, String.class, tenantId, prId)).isEqualTo("partially_ordered");
        assertThat(jdbc.queryForObject("""
                SELECT ordered_qty FROM pr_lines WHERE tenant_id = ? AND id = ?
                """, BigDecimal.class, tenantId, prLineId)).isEqualByComparingTo("2.000");
        assertThat(auditCount(spoId)).isEqualTo(4);
    }

    @Test
    void persistsCommittedDateAndNotesWhenIssuing() throws Exception {
        UUID spoId = createSpo(UUID.randomUUID().toString(), supplierId, quotationId, "5.000", "12.50");
        setContext(tenantId, siteId, actorId);

        MvcResult result = mockMvc.perform(post("/api/v1/spos/" + spoId + "/issue")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"committedDate\":\"2026-11-30\",\"notes\":\"MTC required on delivery\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode issued = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(issued.path("committedDate").asText()).isEqualTo("2026-11-30");
        assertThat(issued.path("notes").asText()).isEqualTo("MTC required on delivery");
        assertThat(jdbc.queryForObject("""
                SELECT expected_delivery::text FROM spos WHERE tenant_id = ? AND id = ?
                """, String.class, tenantId, spoId)).isEqualTo("2026-11-30");
        assertThat(jdbc.queryForObject("""
                SELECT notes FROM spos WHERE tenant_id = ? AND id = ?
                """, String.class, tenantId, spoId)).isEqualTo("MTC required on delivery");
    }

    @Test
    void rejectsMismatchedQuotationSupplierAndExcessPrQuantity() throws Exception {
        UUID otherSupplierId = insert("""
                INSERT INTO suppliers (tenant_id, code, name)
                VALUES (?, ?, 'Other Supplier') RETURNING id::text
                """, tenantId, "SUP-" + UUID.randomUUID().toString().substring(0, 8));
        postCreate(otherSupplierId, quotationId, "5.000", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());

        postCreate(supplierId, otherPrQuotationId, "1.000", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());

        UUID unselectedQuotation = insert("""
                INSERT INTO supplier_quotations
                  (tenant_id, sq_number, pr_id, supplier_id, status, created_by)
                VALUES (?, ?, ?, ?, 'under_review', ?) RETURNING id::text
                """, tenantId, "SQ-" + UUID.randomUUID(), prId, supplierId, actorId);
        postCreate(supplierId, unselectedQuotation, "1.000", UUID.randomUUID().toString())
                .andExpect(status().isConflict());

        postCreate(supplierId, quotationId, "10.001", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());
    }

    @Test
    void createIsIdempotentAndTenantAndRoleScoped() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID spoId = createSpo(key, supplierId, quotationId, "3.000", "12.50");

        setContext(tenantId, siteId, actorId);
        MvcResult replay = mockMvc.perform(post("/api/v1/spos")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplierId, quotationId, "3.000", "12.50")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).path("id").asText())
                .isEqualTo(spoId.toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM spos WHERE tenant_id = ? AND pr_id = ?
                """, Long.class, tenantId, prId)).isEqualTo(1L);

        UUID otherTenant = provisionTenant("spo-crud-other");
        setContext(otherTenant, null, UUID.randomUUID());
        mockMvc.perform(get("/api/v1/spos/" + spoId).with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound());

        setContext(tenantId, siteId, actorId);
        mockMvc.perform(post("/api/v1/spos/" + spoId + "/issue")
                        .with(user("store").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private UUID createSpo(String key, UUID supplier, UUID quote, String quantity, String unitPrice)
            throws Exception {
        setContext(tenantId, siteId, actorId);
        MvcResult result = mockMvc.perform(post("/api/v1/spos")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(supplier, quote, quantity, unitPrice)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions postCreate(
            UUID supplier, UUID quote, String quantity, String key) throws Exception {
        setContext(tenantId, siteId, actorId);
        return mockMvc.perform(post("/api/v1/spos")
                .with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(supplier, quote, quantity, "12.50")));
    }

    private String createBody(UUID supplier, UUID quote, String quantity, String unitPrice) {
        return "{\"supplierId\":\"" + supplier + "\",\"prId\":\"" + prId
                + "\",\"supplierQuotationId\":\"" + quote + "\",\"lines\":[{\"prLineId\":\""
                + prLineId + "\",\"itemId\":\"" + itemId + "\",\"orderedQty\":" + quantity
                + ",\"unitPrice\":" + unitPrice + ",\"uomId\":\"" + uomId + "\"}]}";
    }

    private JsonNode readSpo(UUID spoId) throws Exception {
        setContext(tenantId, siteId, actorId);
        MvcResult result = mockMvc.perform(get("/api/v1/spos/" + spoId)
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

    private long auditCount(UUID spoId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE entity_type = 'spos' AND entity_id = ?
                """, Long.class, spoId);
    }

    private UUID insert(String sql, Object... args) {
        return UUID.fromString(jdbc.queryForObject(sql, String.class, args));
    }
}
