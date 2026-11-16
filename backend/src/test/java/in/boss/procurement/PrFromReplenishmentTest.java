package in.boss.procurement;

import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrRequest;
import in.boss.procurement.api.ProcurementApiDtos.PurchaseRequisitionResponse;
import in.boss.procurement.domain.PurchaseRequisitionService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class PrFromReplenishmentTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private PurchaseRequisitionService prService;

    private UUID tenantId;
    private UUID siteId;
    private UUID storeId;
    private UUID uomId;
    private UUID rawMaterialId;
    private UUID finishedGoodId;

    @BeforeEach
    void setUpFixtures() {
        tenantId = provisionTenant("pr-replenish-test");
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

        storeId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Main RM Store', ?, 'raw_material') RETURNING id
                """, tenantId, siteId, "STR-" + UUID.randomUUID().toString().substring(0, 8));

        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Kilograms', 'kg', TRUE) RETURNING id
                """, tenantId);

        rawMaterialId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, std_cost)
                VALUES (?, ?, 'Copper Wire', 'raw_material', ?, 8.50) RETURNING id
                """, tenantId, "RM-" + UUID.randomUUID().toString().substring(0, 8), uomId);

        finishedGoodId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Motor Stator', 'finished_goods', ?) RETURNING id
                """, tenantId, "FG-" + UUID.randomUUID().toString().substring(0, 8), uomId);
    }

    @Test
    void testCreatePrFromStockDrivenReplenishment() {
        // Setup reorder policy
        UUID policyId = insert("""
                INSERT INTO reorder_policies (tenant_id, item_id, store_id, reorder_point, reorder_qty)
                VALUES (?, ?, ?, 50.000, 200.000) RETURNING id
                """, tenantId, rawMaterialId, storeId);

        // Setup pending replenishment request
        UUID requestId = insert("""
                INSERT INTO replenishment_requests (tenant_id, policy_id, item_id, store_id, requested_qty, status)
                VALUES (?, ?, ?, ?, 200.000, 'pending') RETURNING id
                """, tenantId, policyId, rawMaterialId, storeId);

        CreatePrRequest request = new CreatePrRequest(
                "stock_driven",
                null,
                requestId,
                siteId,
                null,
                "Auto Replenishment",
                LocalDate.now().plusDays(3),
                "normal",
                "Triggered by reorder point breach",
                null
        );

        PurchaseRequisitionResponse pr = prService.createPr(request);

        assertThat(pr.status()).isEqualTo("draft");
        assertThat(pr.originType()).isEqualTo("stock_driven");
        assertThat(pr.lines()).hasSize(1);
        assertThat(pr.lines().get(0).itemId()).isEqualTo(rawMaterialId);
        assertThat(pr.lines().get(0).qty()).isEqualByComparingTo(BigDecimal.valueOf(200.0));

        // Verify replenishment_requests status updated to pr_created and pr_id set
        String repStatus = jdbc.queryForObject(
                "SELECT status FROM replenishment_requests WHERE id = ?",
                String.class, requestId);
        UUID repPrId = jdbc.queryForObject(
                "SELECT pr_id FROM replenishment_requests WHERE id = ?",
                UUID.class, requestId);

        assertThat(repStatus).isEqualTo("pr_created");
        assertThat(repPrId).isEqualTo(pr.id());
    }

    @Test
    void testCreatePrFromProductionJobShortage() {
        UUID jobId = insert("""
                INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by)
                VALUES (?, ?, ?, ?, 100.000, ?, ?) RETURNING id
                """, tenantId, "JOB-" + UUID.randomUUID().toString().substring(0, 8),
                finishedGoodId, siteId, uomId, BossContextHolder.get().userId());

        // Requirement with shortage: required 100, issued 35 -> shortage 65
        insert("""
                INSERT INTO job_material_requirements (tenant_id, job_id, item_id, required_qty, issued_qty, returned_qty, uom_id)
                VALUES (?, ?, ?, 100.000, 35.000, 0.000, ?) RETURNING id
                """, tenantId, jobId, rawMaterialId, uomId);

        CreatePrRequest request = new CreatePrRequest(
                "production_job",
                jobId,
                null,
                siteId,
                null,
                "Job Shortage",
                LocalDate.now().plusDays(2),
                "urgent",
                "Shortage for job execution",
                null
        );

        PurchaseRequisitionResponse pr = prService.createPr(request);

        assertThat(pr.status()).isEqualTo("draft");
        assertThat(pr.originType()).isEqualTo("production_job");
        assertThat(pr.jobId()).isEqualTo(jobId);
        assertThat(pr.priority()).isEqualTo("urgent");
        assertThat(pr.lines()).hasSize(1);
        assertThat(pr.lines().get(0).itemId()).isEqualTo(rawMaterialId);
        assertThat(pr.lines().get(0).qty()).isEqualByComparingTo(BigDecimal.valueOf(65.0));
    }

    @Test
    void testCreatePrFromProductionJobWithoutShortageFails() {
        UUID jobId = insert("""
                INSERT INTO production_jobs (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by)
                VALUES (?, ?, ?, ?, 50.000, ?, ?) RETURNING id
                """, tenantId, "JOB-" + UUID.randomUUID().toString().substring(0, 8),
                finishedGoodId, siteId, uomId, BossContextHolder.get().userId());

        // Requirement fully satisfied: required 50, issued 50 -> shortage 0
        insert("""
                INSERT INTO job_material_requirements (tenant_id, job_id, item_id, required_qty, issued_qty, returned_qty, uom_id)
                VALUES (?, ?, ?, 50.000, 50.000, 0.000, ?) RETURNING id
                """, tenantId, jobId, rawMaterialId, uomId);

        CreatePrRequest request = new CreatePrRequest(
                "production_job",
                jobId,
                null,
                siteId,
                null,
                null,
                null,
                "normal",
                null,
                null
        );

        assertThatThrownBy(() -> prService.createPr(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No material shortages found for production job");
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }
}
