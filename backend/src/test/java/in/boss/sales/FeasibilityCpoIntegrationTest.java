package in.boss.sales;

import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.sales.domain.CustomerPoService;
import in.boss.sales.domain.CpoMaterialAvailabilityService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class FeasibilityCpoIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private CustomerPoService customerPoService;

    @Autowired
    private CpoMaterialAvailabilityService materialAvailabilityService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private MockMvc mockMvc;

    private UUID tenantId;
    private UUID siteId;
    private UUID storeId;
    private UUID uomId;
    private UUID materialId;
    private UUID finishedItemId;
    private UUID cpoId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("cpo-feasibility");
        UUID userId = UUID.randomUUID();
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'CPO Feasibility Entity', 'CPO Feasibility Entity Ltd') RETURNING id
                """, tenantId);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'CPO Feasibility Site', ?) RETURNING id
                """, tenantId, legalEntityId, "SITE-" + suffix);
        storeId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'CPO Feasibility Store', ?, 'raw_material') RETURNING id
                """, tenantId, siteId, "RM-" + suffix);
        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Each', ?, TRUE) RETURNING id
                """, tenantId, "EA-" + suffix);
        materialId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Feasibility Material', 'raw_material', ?) RETURNING id
                """, tenantId, "MAT-" + suffix, uomId);
        finishedItemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Feasibility Finished Item', 'finished_goods', ?) RETURNING id
                """, tenantId, "FG-" + suffix, uomId);
        UUID bomId = insert("""
                INSERT INTO bom_headers (tenant_id, finished_item_id, version, status, created_by)
                VALUES (?, ?, 'v1', 'approved', ?) RETURNING id
                """, tenantId, finishedItemId, userId);
        jdbc.update("""
                INSERT INTO bom_lines (tenant_id, bom_header_id, component_item_id, quantity, uom_id, level)
                VALUES (?, ?, ?, 2, ?, 1)
                """, tenantId, bomId, materialId, uomId);

        UUID customerId = insert("""
                INSERT INTO customers (tenant_id, code, name)
                VALUES (?, ?, 'Feasibility Customer') RETURNING id
                """, tenantId, "CUST-" + suffix);
        cpoId = insert("""
                INSERT INTO cpos
                  (tenant_id, cpo_number, customer_id, site_id, status, order_date, created_by)
                VALUES (?, ?, ?, ?, 'open', CURRENT_DATE, ?) RETURNING id
                """, tenantId, "CPO-" + suffix, customerId, siteId, userId);
        jdbc.update("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 1, ?, 5, ?, 1)
                """, tenantId, cpoId, finishedItemId, uomId);
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), siteId, null));
    }

    @Test
    void submitAndApprovalReserveBomRequirementsOnce() {
        lot("10");

        assertThat(customerPoService.updateStatus(cpoId, "feasibility_pending").status())
                .isEqualTo("feasibility_pending");
        assertThat(customerPoService.updateStatus(cpoId, "feasibility_pending").status())
                .isEqualTo("feasibility_pending");

        assertThat(customerPoService.updateStatus(cpoId, "feasibility_approved").status())
                .isEqualTo("feasibility_approved");
        assertThat(customerPoService.updateStatus(cpoId, "feasibility_approved").status())
                .isEqualTo("feasibility_approved");
        assertThat(activeReservations()).isEqualTo(1);
        assertThat(reservedQty()).isEqualByComparingTo("10.000");
        assertThat(statusAuditCount("STATUS_FEASIBILITY_PENDING")).isEqualTo(1);
        assertThat(statusAuditCount("STATUS_FEASIBILITY_APPROVED")).isEqualTo(1);
    }

    @Test
    void feasibilityEndpointReservesOnceAndReplaysSameRequest() throws Exception {
        lot("10");
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/cpos/" + cpoId + "/feasibility")
                        .with(user("sales-owner").roles("OWNER"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpoId").value(cpoId.toString()))
                .andExpect(jsonPath("$.feasible").value(true));

        assertThat(cpoStatus()).isEqualTo("feasibility_approved");
        assertThat(activeReservations()).isEqualTo(1);

        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/cpos/" + cpoId + "/feasibility")
                        .with(user("sales-owner").roles("OWNER"))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replayed", "true"));

        assertThat(activeReservations()).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "OPS_MANAGER")
    void opsManagerCannotTransitionCpoThroughServiceOrController() throws Exception {
        assertThatThrownBy(() -> customerPoService.updateStatus(cpoId, "feasibility_pending"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> customerPoService.checkFeasibility(cpoId))
                .isInstanceOf(AccessDeniedException.class);

        mockMvc.perform(patch("/api/v1/cpos/" + cpoId + "/status")
                        .with(user("ops-manager").roles("OPS_MANAGER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"status\":\"feasibility_pending\"}"))
                .andExpect(status().isForbidden());

        assertThat(cpoStatus()).isEqualTo("open");
        assertThat(activeReservations()).isZero();
    }

    @Test
    void approvalWithShortageLeavesCpoPendingAndReservesNothing() {
        lot("9");
        customerPoService.updateStatus(cpoId, "feasibility_pending");

        assertThatThrownBy(() -> customerPoService.updateStatus(cpoId, "feasibility_approved"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(customerPoService.get(cpoId).status()).isEqualTo("feasibility_pending");
        assertThat(activeReservations()).isZero();
        assertThat(reservedQty()).isEqualByComparingTo("0.000");
    }

    @Test
    void feasibilityShortageStaysPendingAndCanBeApprovedAfterStockArrives() {
        lot("9");

        assertThat(customerPoService.checkFeasibility(cpoId).feasible()).isFalse();
        assertThat(cpoStatus()).isEqualTo("feasibility_pending");
        assertThat(activeReservations()).isZero();

        lot("1");
        assertThat(customerPoService.updateStatus(cpoId, "feasibility_approved").status())
                .isEqualTo("feasibility_approved");
        assertThat(reservedQty()).isEqualByComparingTo("10.000");
    }

    @Test
    void cancellationReleasesReservationsAfterApproval() {
        lot("10");
        customerPoService.updateStatus(cpoId, "feasibility_pending");
        customerPoService.updateStatus(cpoId, "feasibility_approved");

        assertThat(customerPoService.updateStatus(cpoId, "cancelled").status()).isEqualTo("cancelled");
        assertThat(activeReservations()).isZero();
        assertThat(reservedQty()).isEqualByComparingTo("0.000");
    }

    @Test
    void materialPreviewConsolidatesRequirementsAndUsesOnlyEligibleSiteStock() throws Exception {
        UUID anotherProduct = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Second Product', 'finished_goods', ?) RETURNING id
                """, tenantId, "FG-" + UUID.randomUUID(), uomId);
        UUID secondBom = insert("""
                INSERT INTO bom_headers (tenant_id, finished_item_id, version, status, created_by)
                VALUES (?, ?, 'v1', 'approved', ?) RETURNING id
                """, tenantId, anotherProduct, BossContextHolder.get().userId());
        jdbc.update("""
                INSERT INTO bom_lines (tenant_id, bom_header_id, component_item_id, quantity, uom_id, level)
                VALUES (?, ?, ?, 3, ?, 1)
                """, tenantId, secondBom, materialId, uomId);
        jdbc.update("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 2, ?, 2, ?, 1)
                """, tenantId, cpoId, anotherProduct, uomId);

        UUID eligibleLot = lot("10");
        jdbc.update("UPDATE material_lots SET reserved_qty = 3 WHERE tenant_id = ? AND id = ?",
                tenantId, eligibleLot);
        jdbc.update("""
                INSERT INTO stock_reservation_records
                  (tenant_id, lot_id, item_id, reserved_qty, reference_type, reference_id, status)
                VALUES (?, ?, ?, 3, 'other', ?, 'active')
                """, tenantId, eligibleLot, materialId, UUID.randomUUID());
        UUID expired = lot("50");
        jdbc.update("UPDATE material_lots SET expiry_date = CURRENT_DATE - 1 WHERE tenant_id = ? AND id = ?",
                tenantId, expired);
        UUID quarantined = lot("50");
        jdbc.update("UPDATE material_lots SET status = 'quarantine' WHERE tenant_id = ? AND id = ?",
                tenantId, quarantined);
        UUID otherSite = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                SELECT tenant_id, legal_entity_id, 'Other site', ? FROM physical_sites WHERE id = ?
                RETURNING id
                """, "OTHER-" + UUID.randomUUID(), siteId);
        UUID otherStore = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Other store', ?, 'raw_material') RETURNING id
                """, tenantId, otherSite, "OTHER-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO material_lots (tenant_id, item_id, store_id, quantity, reserved_qty, status)
                VALUES (?, ?, ?, 100, 0, 'available')
                """, tenantId, materialId, otherStore);

        mockMvc.perform(get("/api/v1/cpos/" + cpoId + "/material-availability")
                        .with(user("sales-owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteId").value(siteId.toString()))
                .andExpect(jsonPath("$.basis").value("CURRENT_APPROVED_FIXED_BOM"))
                .andExpect(jsonPath("$.limitations[1]").value("Specification-driven calculations are not supported."))
                .andExpect(jsonPath("$.materials.length()").value(1))
                .andExpect(jsonPath("$.materials[0].itemId").value(materialId.toString()))
                .andExpect(jsonPath("$.materials[0].uomId").value(uomId.toString()))
                .andExpect(jsonPath("$.materials[0].requiredQty").value(16))
                .andExpect(jsonPath("$.materials[0].eligibleStockQty").value(10))
                .andExpect(jsonPath("$.materials[0].reservedQty").value(3))
                .andExpect(jsonPath("$.materials[0].availableQty").value(7))
                .andExpect(jsonPath("$.materials[0].shortageQty").value(9));
        assertThat(cpoStatus()).isEqualTo("open");
        assertThat(activeReservations()).isZero();
        assertThat(reservedQty()).isEqualByComparingTo("3");
    }

    @Test
    void previewKeepsRequirementPrecisionBeforeComputingShortage() {
        jdbc.update("UPDATE bom_lines SET quantity = 0.00001 WHERE tenant_id = ?", tenantId);
        var line = materialAvailabilityService.preview(cpoId).materials().get(0);
        assertThat(line.requiredQty()).isEqualByComparingTo("0.00005");
        assertThat(line.shortageQty()).isEqualByComparingTo("0.00005");
    }

    @Test
    void previewRejectsMissingBomAndMismatchedUnits() {
        jdbc.update("UPDATE bom_headers SET status = 'draft' WHERE tenant_id = ? AND finished_item_id = ?",
                tenantId, finishedItemId);
        assertThatThrownBy(() -> materialAvailabilityService.preview(cpoId))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).contains("no approved BOM");
                });
        jdbc.update("UPDATE bom_headers SET status = 'approved' WHERE tenant_id = ? AND finished_item_id = ?",
                tenantId, finishedItemId);
        UUID otherUom = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Other', ?, TRUE) RETURNING id
                """, tenantId, "OTHER-" + UUID.randomUUID());
        jdbc.update("UPDATE bom_lines SET uom_id = ? WHERE tenant_id = ?", otherUom, tenantId);
        assertThatThrownBy(() -> materialAvailabilityService.preview(cpoId))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).contains("base UOM"));
    }

    @Test
    void previewRejectsOptionalBomLinesAndDirectReservations() {
        jdbc.update("UPDATE bom_lines SET is_optional = TRUE WHERE tenant_id = ?", tenantId);
        assertThatThrownBy(() -> materialAvailabilityService.preview(cpoId))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).contains("Optional"));
        jdbc.update("UPDATE bom_lines SET is_optional = FALSE WHERE tenant_id = ?", tenantId);
        UUID lotId = lot("10");
        UUID lineId = jdbc.queryForObject("SELECT id FROM cpo_lines WHERE tenant_id = ? AND cpo_id = ?",
                UUID.class, tenantId, cpoId);
        jdbc.update("UPDATE material_lots SET reserved_qty = 1 WHERE tenant_id = ? AND id = ?", tenantId, lotId);
        jdbc.update("""
                INSERT INTO stock_reservation_records
                  (tenant_id, lot_id, item_id, reserved_qty, reference_type, reference_id, cpo_line_id, status)
                VALUES (?, ?, ?, 1, 'cpo', ?, ?, 'active')
                """, tenantId, lotId, materialId, cpoId, lineId);
        assertThatThrownBy(() -> materialAvailabilityService.preview(cpoId))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).contains("direct line reservations"));
        assertThat(activeReservations()).isEqualTo(1);
        assertThat(reservedQty()).isEqualByComparingTo("1");
    }

    @Test
    void previewEnforcesTenantAndSiteAccess() {
        UUID userId = BossContextHolder.get().userId();
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), UUID.randomUUID(), null));
        assertThatThrownBy(() -> materialAvailabilityService.preview(cpoId))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        UUID otherTenant = provisionTenant("cpo-preview-other");
        BossContextHolder.set(new BossContext(otherTenant, userId, Set.of("OWNER"), null, null));
        assertThatThrownBy(() -> materialAvailabilityService.preview(cpoId))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void previewRejectsMaterialReservationsWithoutHistoricalBomSnapshot() {
        lot("10");
        customerPoService.checkFeasibility(cpoId);
        assertThatThrownBy(() -> materialAvailabilityService.preview(cpoId))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).contains("without a BOM snapshot"));
        assertThat(activeReservations()).isEqualTo(1);
        assertThat(reservedQty()).isEqualByComparingTo("10");
    }

    private UUID lot(String quantity) {
        return inventoryService.createLot(new CreateLotRequest(materialId, storeId, new BigDecimal(quantity),
                BigDecimal.ONE, null, null, null, null)).id();
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    private int activeReservations() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_id = ? AND status = 'active'
                """, Integer.class, tenantId, cpoId);
    }

    private String cpoStatus() {
        return jdbc.queryForObject("SELECT status FROM cpos WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, cpoId);
    }

    private BigDecimal reservedQty() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM material_lots
                 WHERE tenant_id = ? AND item_id = ?
                """, BigDecimal.class, tenantId, materialId);
    }

    private int statusAuditCount(String action) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                 WHERE tenant_id = ? AND entity_type = 'CPO' AND entity_id = ? AND action = ?
                """, Integer.class, tenantId, cpoId, action);
    }
}
