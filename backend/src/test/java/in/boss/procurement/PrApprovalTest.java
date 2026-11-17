package in.boss.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrRequest;
import in.boss.procurement.api.ProcurementApiDtos.PurchaseRequisitionResponse;
import in.boss.procurement.api.ProcurementApiDtos.RejectPrRequest;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class PrApprovalTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private PurchaseRequisitionService prService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID siteId;
    private UUID uomId;
    private UUID item1Id;
    private UUID item2Id;
    private UUID supplierId;

    @BeforeEach
    void setUpFixtures() {
        tenantId = provisionTenant("pr-approval-test");
        setTenantContext(tenantId, "OWNER");
        UUID userId = BossContextHolder.get().userId();

        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Procurement Entity', 'Procurement Entity Ltd') RETURNING id
                """, tenantId);

        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Procurement Site', ?) RETURNING id
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 8));

        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), siteId, null));

        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Units', 'u', TRUE) RETURNING id
                """, tenantId);

        item1Id = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, std_cost)
                VALUES (?, ?, 'Item One', 'raw_material', ?, 15.00) RETURNING id
                """, tenantId, "ITM-" + UUID.randomUUID().toString().substring(0, 8), uomId);

        item2Id = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, std_cost)
                VALUES (?, ?, 'Item Two', 'raw_material', ?, 30.00) RETURNING id
                """, tenantId, "ITM-" + UUID.randomUUID().toString().substring(0, 8), uomId);

        supplierId = insert("""
                INSERT INTO suppliers (tenant_id, code, name)
                VALUES (?, ?, 'Primary Supplier Ltd') RETURNING id
                """, tenantId, "SUP-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void testSubmitPrStateTransition() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        , null));
        assertThat(pr.status()).isEqualTo("draft");

        PurchaseRequisitionResponse submitted = prService.submitPr(pr.id());
        assertThat(submitted.status()).isEqualTo("submitted");

        // Cannot submit again
        assertThatThrownBy(() -> prService.submitPr(pr.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only draft purchase requisitions can be submitted");
    }

    @Test
    void testApprovePrByAuthorizedRoles() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        , null));
        prService.submitPr(pr.id());

        // Approved by Purchase Manager role
        setTenantContext(tenantId, "PURCHASE_MANAGER");
        PurchaseRequisitionResponse approved = prService.approvePr(pr.id());

        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.approvedBy()).isEqualTo(BossContextHolder.get().userId());
        assertThat(approved.approvedAt()).isNotNull();
    }

    @Test
    void testApprovePrForbiddenForUnauthorizedRoles() throws Exception {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        , null));
        prService.submitPr(pr.id());

        // STORE_KEEPER cannot approve
        mockMvc.perform(post("/api/v1/prs/" + pr.id() + "/approve")
                        .with(user("storekeeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void testCannotApproveDraftPr() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        , null));

        assertThatThrownBy(() -> prService.approvePr(pr.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only submitted purchase requisitions can be approved");
    }

    @Test
    void testRejectPrWithReason() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        , null));
        prService.submitPr(pr.id());

        PurchaseRequisitionResponse rejected = prService.rejectPr(pr.id(), new RejectPrRequest("Exceeds department budget"));

        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.rejectionReason()).isEqualTo("Exceeds department budget");
        assertThat(rejected.rejectedBy()).isEqualTo(BossContextHolder.get().userId());
        assertThat(rejected.rejectedAt()).isNotNull();
    }

    @Test
    void testRejectPrWithoutReasonFails() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        , null));
        prService.submitPr(pr.id());

        assertThatThrownBy(() -> prService.rejectPr(pr.id(), new RejectPrRequest("  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Rejection reason is required");
    }

    @Test
    void testProcurementCoverageLifecycle() {
        // Line 1: qty 10, Line 2: qty 20
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(
                        new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null),
                        new CreatePrLineRequest(item2Id, BigDecimal.valueOf(20), null, uomId, null, null, null, null, null)
                )
        , null));
        prService.submitPr(pr.id());
        prService.approvePr(pr.id());

        PurchaseRequisitionResponse approvedPr = prService.getPr(pr.id());
        assertThat(approvedPr.status()).isEqualTo("approved");
        UUID prLine1Id = approvedPr.lines().get(0).id();
        UUID prLine2Id = approvedPr.lines().get(1).id();

        // 1. Partial ordered: Create active issued SPO with 5 units of Line 1
        UUID spo1Id = insertSpo("issued", pr.id());
        insertSpoLine(spo1Id, prLine1Id, 1, item1Id, BigDecimal.valueOf(5), BigDecimal.valueOf(15.0));

        PurchaseRequisitionResponse partialPr = prService.recalculateCoverage(pr.id());
        assertThat(partialPr.status()).isEqualTo("partially_ordered");
        assertThat(partialPr.lines().get(0).orderedQty()).isEqualByComparingTo(BigDecimal.valueOf(5));

        // 2. Cancelled SPOs do not count towards coverage
        UUID cancelledSpoId = insertSpo("cancelled", pr.id());
        insertSpoLine(cancelledSpoId, prLine2Id, 1, item2Id, BigDecimal.valueOf(20), BigDecimal.valueOf(30.0));

        PurchaseRequisitionResponse stillPartial = prService.recalculateCoverage(pr.id());
        assertThat(stillPartial.status()).isEqualTo("partially_ordered");
        assertThat(stillPartial.lines().get(1).orderedQty()).isEqualByComparingTo(BigDecimal.ZERO);

        // 3. Complete ordering: Add active SPO with remaining (Line 1: 5, Line 2: 20)
        UUID spo2Id = insertSpo("issued", pr.id());
        insertSpoLine(spo2Id, prLine1Id, 1, item1Id, BigDecimal.valueOf(5), BigDecimal.valueOf(15.0));
        insertSpoLine(spo2Id, prLine2Id, 2, item2Id, BigDecimal.valueOf(20), BigDecimal.valueOf(30.0));

        PurchaseRequisitionResponse fullyOrdered = prService.recalculateCoverage(pr.id());
        assertThat(fullyOrdered.status()).isEqualTo("ordered");
        assertThat(fullyOrdered.totalOrderedQty()).isEqualByComparingTo(BigDecimal.valueOf(30));

        // 4. Cancel active SPO 2 -> coverage drops back to partially_ordered
        jdbc.update("UPDATE spos SET status = 'cancelled' WHERE id = ?", spo2Id);
        PurchaseRequisitionResponse backToPartial = prService.recalculateCoverage(pr.id());
        assertThat(backToPartial.status()).isEqualTo("partially_ordered");
        assertThat(backToPartial.lines().get(0).orderedQty()).isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(backToPartial.lines().get(1).orderedQty()).isEqualByComparingTo(BigDecimal.ZERO);

        // 5. Cancel active SPO 1 -> coverage drops back to approved
        jdbc.update("UPDATE spos SET status = 'cancelled' WHERE id = ?", spo1Id);
        PurchaseRequisitionResponse backToApproved = prService.recalculateCoverage(pr.id());
        assertThat(backToApproved.status()).isEqualTo("approved");
        assertThat(backToApproved.totalOrderedQty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testCannotCancelPrWithActiveSupplierCommitments() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        , null));
        prService.submitPr(pr.id());
        prService.approvePr(pr.id());

        UUID prLineId = pr.lines().get(0).id();
        UUID spoId = insertSpo("issued", pr.id());
        insertSpoLine(spoId, prLineId, 1, item1Id, BigDecimal.valueOf(5), BigDecimal.valueOf(15.0));

        // Attempt to cancel PR with active supplier commitments -> 409 Conflict
        assertThatThrownBy(() -> prService.cancelPr(pr.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot cancel Purchase Requisition because active supplier commitments exist");

        // Cancel the SPO
        jdbc.update("UPDATE spos SET status = 'cancelled' WHERE id = ?", spoId);

        // Now cancellation succeeds
        PurchaseRequisitionResponse cancelled = prService.cancelPr(pr.id());
        assertThat(cancelled.status()).isEqualTo("cancelled");
    }

    private UUID insertSpo(String status, UUID prId) {
        UUID quoteId = insert("""
                INSERT INTO supplier_quotations (tenant_id, sq_number, pr_id, supplier_id, status, created_by)
                VALUES (?, ?, ?, ?, 'selected', ?) RETURNING id
                """, tenantId, "SQ-" + UUID.randomUUID().toString().substring(0, 8),
                prId, supplierId, BossContextHolder.get().userId());
        return insert("""
                INSERT INTO spos (tenant_id, spo_number, sq_id, pr_id, supplier_id, site_id, status, order_date, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_DATE, ?) RETURNING id
                """,
                tenantId, "SPO-" + UUID.randomUUID().toString().substring(0, 8),
                quoteId, prId, supplierId, siteId, status, BossContextHolder.get().userId());
    }

    private void insertSpoLine(UUID spoId, UUID prLineId, int lineNo, UUID itemId, BigDecimal qty, BigDecimal unitPrice) {
        insert("""
                INSERT INTO spo_lines (tenant_id, spo_id, pr_line_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """,
                tenantId, spoId, prLineId, lineNo, itemId, qty, uomId, unitPrice);
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }
}
