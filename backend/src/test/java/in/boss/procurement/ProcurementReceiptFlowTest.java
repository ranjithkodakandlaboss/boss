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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class ProcurementReceiptFlowTest extends BaseMasterDataIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PurchaseRequisitionService prService;

    private UUID tenantId;
    private UUID actorId;
    private UUID siteId;
    private UUID storeId;
    private UUID supplierId;
    private UUID customerId;
    private UUID uomId;
    private UUID itemId;
    private UUID prId;
    private UUID prLineId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("receipt-flow");
        actorId = UUID.randomUUID();
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Receipt Entity', 'Receipt Entity Ltd') RETURNING id::text
                """, tenantId);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Receipt Site', ?) RETURNING id::text
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 8));
        setContext(tenantId, siteId, actorId);
        storeId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Raw Store', ?, 'raw_material') RETURNING id::text
                """, tenantId, siteId, "ST-" + UUID.randomUUID().toString().substring(0, 6));
        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Each', ?, TRUE) RETURNING id::text
                """, tenantId, "EA-" + UUID.randomUUID().toString().substring(0, 6));
        itemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Receipt Item', 'raw_material', ?) RETURNING id::text
                """, tenantId, "ITEM-" + UUID.randomUUID().toString().substring(0, 8), uomId);
        supplierId = insert("""
                INSERT INTO suppliers (tenant_id, code, name)
                VALUES (?, ?, 'Receipt Supplier') RETURNING id::text
                """, tenantId, "SUP-" + UUID.randomUUID().toString().substring(0, 8));
        customerId = insert("""
                INSERT INTO customers (tenant_id, code, name)
                VALUES (?, ?, 'Receipt Customer') RETURNING id::text
                """, tenantId, "CUS-" + UUID.randomUUID().toString().substring(0, 8));
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                java.util.List.of(new CreatePrLineRequest(
                        itemId, new BigDecimal("10.000"), null, uomId, null, null, null, null, null)), null));
        prService.submitPr(pr.id());
        prService.approvePr(pr.id());
        prId = pr.id();
        prLineId = pr.lines().get(0).id();
    }

    @Test
    void receiptPostsStockOnceAndReturnRemovesIt() throws Exception {
        UUID spoId = issuedSpo(new BigDecimal("10.000"));
        String spoLineId = readSpo(spoId).path("lines").get(0).path("id").asText();
        String key = UUID.randomUUID().toString();
        String body = grnBody(spoId, spoLineId, "4.000", "0", "4.000");

        MvcResult created = postJson("/api/v1/grns", key, body).andExpect(status().isCreated()).andReturn();
        JsonNode grn = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(grn.path("status").asText()).isEqualTo("draft");
        assertThat(grn.path("lines").get(0).path("acceptedQty").decimalValue()).isEqualByComparingTo("0");
        assertThat(grn.path("lines").get(0).path("lotStatuses").get(0).asText()).isEqualTo("quarantine");
        UUID lotId = UUID.fromString(grn.path("lines").get(0).path("lotIds").get(0).asText());
        assertThat(lotQuantity(lotId)).isEqualByComparingTo("4.000");
        assertThat(availableQuantity()).isEqualByComparingTo("0");
        assertThat(movementCount(lotId, "receipt")).isEqualTo(1);

        setContext(tenantId, siteId, actorId);
        MvcResult replay = postJson("/api/v1/grns", key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).path("id").asText())
                .isEqualTo(grn.path("id").asText());
        assertThat(lotQuantity(lotId)).isEqualByComparingTo("4.000");

        setContext(tenantId, siteId, actorId);
        postJson("/api/v1/grns", UUID.randomUUID().toString(), grnBody(spoId, spoLineId, "7.000", "0", "7.000"))
                .andExpect(status().isConflict());

        UUID inspectionId = startInspection(grn.path("id").asText(), lotId);
        assertThat(startInspection(grn.path("id").asText(), lotId)).isEqualTo(inspectionId);
        postJson("/api/v1/quality-inspections/" + inspectionId + "/complete", UUID.randomUUID().toString(),
                "{\"acceptedQty\":0,\"rejectedQty\":0,\"hold\":true,\"notes\":\"Waiting for the mill certificate\"}")
                .andExpect(status().isOk());
        assertThat(lotStatus(lotId)).isEqualTo("quarantine");
        assertThat(availableQuantity()).isEqualByComparingTo("0");
        postJson("/api/v1/quality-inspections/" + inspectionId + "/complete", UUID.randomUUID().toString(),
                "{\"acceptedQty\":4.000,\"rejectedQty\":0,\"hold\":false}")
                .andExpect(status().isOk());
        assertThat(lotStatus(lotId)).isEqualTo("available");
        assertThat(availableQuantity()).isEqualByComparingTo("4.000");
        assertThat(readSpo(spoId).path("status").asText()).isEqualTo("partially_received");
        postJson("/api/v1/quality-inspections/" + inspectionId + "/complete", UUID.randomUUID().toString(),
                "{\"acceptedQty\":4.000,\"rejectedQty\":0,\"hold\":false}")
                .andExpect(status().isOk());
        assertThat(lotQuantity(lotId)).isEqualByComparingTo("4.000");

        setContext(tenantId, siteId, actorId);
        MvcResult second = postJson("/api/v1/grns", UUID.randomUUID().toString(),
                grnBody(spoId, spoLineId, "6.000", "0", "6.000")).andExpect(status().isCreated()).andReturn();
        JsonNode secondGrn = objectMapper.readTree(second.getResponse().getContentAsString());
        UUID secondLot = UUID.fromString(secondGrn.path("lines").get(0).path("lotIds").get(0).asText());
        UUID secondInspection = startInspection(secondGrn.path("id").asText(), secondLot);
        postJson("/api/v1/quality-inspections/" + secondInspection + "/complete", UUID.randomUUID().toString(),
                "{\"acceptedQty\":5.000,\"rejectedQty\":1.000,\"hold\":false,\"notes\":\"Accepted under concession\"}")
                .andExpect(status().isOk());
        assertThat(lotQuantity(lotId)).isEqualByComparingTo("4.000");
        assertThat(lotQuantity(secondLot)).isEqualByComparingTo("1.000");
        assertThat(lotStatus(secondLot)).isEqualTo("quarantine");
        assertThat(availableQuantity()).isEqualByComparingTo("9.000");

        String returnBody = """
                {"grnId":"%s","reason":"Damaged in transit","lines":[{"grnLineId":"%s","lotId":"%s","quantity":1.500}]}
                """.formatted(grn.path("id").asText(), grn.path("lines").get(0).path("id").asText(), lotId);
        setContext(tenantId, siteId, actorId);
        postJson("/api/v1/supplier-returns", UUID.randomUUID().toString(), returnBody).andExpect(status().isCreated());
        assertThat(lotQuantity(lotId)).isEqualByComparingTo("2.500");
        assertThat(movementCount(lotId, "issue")).isEqualTo(1);

        setContext(tenantId, siteId, actorId);
        JsonNode performance = objectMapper.readTree(mockMvc.perform(get("/api/v1/supplier-performance")
                        .with(user("owner").roles("OWNER"))
                        .param("supplierId", supplierId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(performance.path("totalReceipts").asInt()).isEqualTo(2);
        assertThat(performance.path("qualityRejectionPct").decimalValue()).isGreaterThan(BigDecimal.ZERO);

        UUID otherTenant = provisionTenant("receipt-other");
        setContext(otherTenant, null, UUID.randomUUID());
        mockMvc.perform(get("/api/v1/grns/" + grn.path("id").asText()).with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void declineAndAmendmentFollowTheApprovedTransitions() throws Exception {
        UUID spoId = issuedSpo(new BigDecimal("10.000"));
        String lineId = readSpo(spoId).path("lines").get(0).path("id").asText();
        setContext(tenantId, siteId, actorId);
        postJson("/api/v1/spos/" + spoId + "/decline", UUID.randomUUID().toString(),
                "{\"reason\":\"Mill batch sold out\"}").andExpect(status().isOk());
        assertThat(readSpo(spoId).path("status").asText()).isEqualTo("declined");
        assertThat(readSpo(spoId).path("declineReason").asText()).isEqualTo("Mill batch sold out");

        UUID second = issuedSpo(new BigDecimal("8.000"));
        String secondLine = readSpo(second).path("lines").get(0).path("id").asText();
        setContext(tenantId, siteId, actorId);
        MvcResult amendment = postJson("/api/v1/spos/" + second + "/amendments", UUID.randomUUID().toString(), """
                {"reason":"qty_change","changeNote":"Reduce to the confirmed mill quantity","lineId":"%s","quantity":6.000}
                """.formatted(secondLine)).andExpect(status().isCreated()).andReturn();
        String amendmentId = objectMapper.readTree(amendment.getResponse().getContentAsString()).path("id").asText();
        assertThat(readSpo(second).path("lines").get(0).path("orderedQty").decimalValue()).isEqualByComparingTo("8.000");
        setContext(tenantId, siteId, actorId);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/spos/" + second + "/amendments/" + amendmentId + "/files")
                        .file(new MockMultipartFile("file", "concession.pdf", "application/pdf", "evidence".getBytes()))
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        String fileId = objectMapper.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();
        setContext(tenantId, siteId, actorId);
        mockMvc.perform(multipart("/api/v1/spos/" + second + "/amendments/" + amendmentId + "/files")
                        .file(new MockMultipartFile("file", "notes.exe", "application/octet-stream", "no".getBytes()))
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        setContext(tenantId, siteId, actorId);
        mockMvc.perform(get("/api/v1/spos/" + second + "/amendments/" + amendmentId + "/files/" + fileId)
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        assertThat(auditCount("spo_amendment_files", UUID.fromString(fileId))).isEqualTo(1);
        UUID otherTenant = provisionTenant("amendment-file-other");
        setContext(otherTenant, null, UUID.randomUUID());
        mockMvc.perform(get("/api/v1/spos/" + second + "/amendments/" + amendmentId + "/files/" + fileId)
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound());

        BossContextHolder.set(new BossContext(tenantId, actorId, Set.of("PURCHASE_MANAGER"), siteId, null));
        mockMvc.perform(post("/api/v1/spos/" + second + "/amendments/" + amendmentId + "/approve")
                        .with(user("buyer").roles("PURCHASE_MANAGER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        setContext(tenantId, siteId, actorId);
        postJson("/api/v1/spos/" + second + "/amendments/" + amendmentId + "/approve", UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk());
        assertThat(readSpo(second).path("lines").get(0).path("orderedQty").decimalValue()).isEqualByComparingTo("6.000");
        assertThat(readSpo(second).path("revision").asInt()).isEqualTo(2);
        assertThat(lineId).isNotBlank();
    }

    @Test
    void availabilityIsPersistedAndDoesNotInventStock() throws Exception {
        UUID cpoId = insert("""
                INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, status, order_date, created_by)
                VALUES (?, ?, ?, ?, 'open', CURRENT_DATE, ?) RETURNING id::text
                """, tenantId, "CPO-" + UUID.randomUUID().toString().substring(0, 8), customerId, siteId, actorId);
        UUID available = insert("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 1, ?, 4, ?, 10) RETURNING id::text
                """, tenantId, cpoId, itemId, uomId);
        UUID partial = insert("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 2, ?, 5, ?, 10) RETURNING id::text
                """, tenantId, cpoId, itemId, uomId);
        setContext(tenantId, siteId, actorId);
        postJson("/api/v1/cpos/" + cpoId + "/availability", UUID.randomUUID().toString(), """
                {"lines":[
                  {"lineId":"%s","availability":"available"},
                  {"lineId":"%s","availability":"partial","shortageQty":2}
                ]}
                """.formatted(available, partial)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT availability FROM cpo_lines WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, available)).isEqualTo("available");
        assertThat(jdbc.queryForObject(
                "SELECT shortage_qty FROM cpo_lines WHERE tenant_id = ? AND id = ?",
                BigDecimal.class, tenantId, partial)).isEqualByComparingTo("2.000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_requisitions WHERE tenant_id = ?",
                Long.class, tenantId)).isEqualTo(1L);
    }

    private UUID issuedSpo(BigDecimal quantity) throws Exception {
        UUID quotationId = insert("""
                INSERT INTO supplier_quotations
                  (tenant_id, sq_number, pr_id, supplier_id, status, created_by)
                VALUES (?, ?, ?, ?, 'selected', ?) RETURNING id::text
                """, tenantId, "SQ-" + UUID.randomUUID(), prId, supplierId, actorId);
        insert("""
                INSERT INTO supplier_quotation_lines
                  (tenant_id, sq_id, pr_line_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, ?, 1, ?, ?, ?, 12.50) RETURNING id::text
                """, tenantId, quotationId, prLineId, itemId, quantity, uomId);
        setContext(tenantId, siteId, actorId);
        MvcResult created = postJson("/api/v1/spos", UUID.randomUUID().toString(), """
                {"supplierId":"%s","prId":"%s","supplierQuotationId":"%s","lines":[
                  {"prLineId":"%s","itemId":"%s","orderedQty":%s,"unitPrice":12.50,"uomId":"%s"}]}
                """.formatted(supplierId, prId, quotationId, prLineId, itemId, quantity, uomId))
                .andExpect(status().isCreated()).andReturn();
        UUID spoId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText());
        jdbc.update("UPDATE spos SET expected_delivery = ? WHERE tenant_id = ? AND id = ?",
                LocalDate.now().plusDays(7), tenantId, spoId);
        setContext(tenantId, siteId, actorId);
        postJson("/api/v1/spos/" + spoId + "/issue", UUID.randomUUID().toString(), "{}").andExpect(status().isOk());
        return spoId;
    }

    private String grnBody(UUID spoId, String spoLineId, String received, String rejected, String lotQty) {
        return """
                {"spoId":"%s","receivedDate":"%s","deliveryChallan":"DC-%s","storeId":"%s","lines":[
                  {"spoLineId":"%s","receivedQty":%s,"rejectedQty":%s,"lots":[{"lotNumber":"HEAT-%s","qty":%s}]}
                ]}
                """.formatted(spoId, LocalDate.now(), UUID.randomUUID().toString().substring(0, 6),
                storeId, spoLineId, received, rejected, UUID.randomUUID().toString().substring(0, 4), lotQty);
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String key, String body) throws Exception {
        setContext(tenantId, siteId, actorId);
        return mockMvc.perform(post(path)
                .with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode readSpo(UUID spoId) throws Exception {
        setContext(tenantId, siteId, actorId);
        MvcResult result = mockMvc.perform(get("/api/v1/spos/" + spoId).with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID startInspection(String grnId, UUID lotId) throws Exception {
        setContext(tenantId, siteId, actorId);
        MvcResult result = postJson("/api/v1/grns/" + grnId + "/inspections?lotId=" + lotId,
                UUID.randomUUID().toString(), "{}").andExpect(status().isOk()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private BigDecimal lotQuantity(UUID lotId) {
        return jdbc.queryForObject(
                "SELECT quantity FROM material_lots WHERE tenant_id = ? AND id = ?",
                BigDecimal.class, tenantId, lotId);
    }

    private String lotStatus(UUID lotId) {
        return jdbc.queryForObject(
                "SELECT status FROM material_lots WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, lotId);
    }

    private BigDecimal availableQuantity() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM material_lots
                WHERE tenant_id = ? AND item_id = ? AND status IN ('available', 'reserved')
                """, BigDecimal.class, tenantId, itemId);
    }

    private long auditCount(String entityType, UUID entityId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_type = ? AND entity_id = ?",
                Long.class, entityType, entityId);
        return count == null ? 0 : count;
    }

    private int movementCount(UUID lotId, String type) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                WHERE tenant_id = ? AND lot_id = ? AND movement_type = ?
                """, Integer.class, tenantId, lotId, type);
        return count == null ? 0 : count;
    }

    private void setContext(UUID tenant, UUID site, UUID actor) {
        BossContextHolder.set(new BossContext(tenant, actor, Set.of("OWNER"), site, null));
    }

    private UUID insert(String sql, Object... args) {
        return UUID.fromString(jdbc.queryForObject(sql, String.class, args));
    }
}
