package in.boss.sales;

import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.sales.domain.CustomerPoService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(roles = "OWNER")
class FeasibilityCpoIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private CustomerPoService customerPoService;

    @Autowired
    private InventoryService inventoryService;

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
    void cancellationReleasesReservationsAfterApproval() {
        lot("10");
        customerPoService.updateStatus(cpoId, "feasibility_pending");
        customerPoService.updateStatus(cpoId, "feasibility_approved");

        assertThat(customerPoService.updateStatus(cpoId, "cancelled").status()).isEqualTo("cancelled");
        assertThat(activeReservations()).isZero();
        assertThat(reservedQty()).isEqualByComparingTo("0.000");
    }

    private void lot(String quantity) {
        inventoryService.createLot(new CreateLotRequest(materialId, storeId, new BigDecimal(quantity),
                BigDecimal.ONE, null, null, null, null));
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
