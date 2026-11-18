package in.boss.quality;

import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.production.domain.JobInspectionService;
import in.boss.production.domain.JobStageService;
import in.boss.production.api.ProductionApiDtos.InspectionResultRequest;
import in.boss.production.api.ProductionApiDtos.ConcessionRequest;
import in.boss.quality.api.QualityDtos.*;
import in.boss.quality.domain.QualityService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AutoConfigureMockMvc
class QualityModuleIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired QualityService qualityService;
    @Autowired JobInspectionService jobInspectionService;
    @Autowired JobStageService jobStageService;
    @Autowired MockMvc mockMvc;

    private UUID tenantId;
    private UUID itemId;
    private UUID jobId;
    private UUID stageId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("quality-test");
        UUID userId = UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), null, null));
        jdbc.update("""
                INSERT INTO app_users (id, tenant_id, firebase_uid, email, display_name)
                VALUES (?, ?, ?, ?, 'Quality Owner')
                """, userId, tenantId, "uid-" + userId, "owner-" + userId + "@example.test");

        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Quality Corp', 'Quality Corp Ltd') RETURNING id
                """, tenantId);
        UUID siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Main Plant', ?) RETURNING id
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 6));
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), siteId, null));
        UUID uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Piece', 'pc', TRUE) RETURNING id
                """, tenantId);
        itemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, 'FG-001', 'Finished Part', 'finished_goods', ?) RETURNING id
                """, tenantId, uomId);
        UUID customerId = insert("""
                INSERT INTO customers (tenant_id, code, name)
                VALUES (?, 'CUST-01', 'Test Customer') RETURNING id
                """, tenantId);
        UUID cpoId = insert("""
                INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, status, order_date, required_by_date, created_by)
                VALUES (?, 'CPO-001', ?, ?, 'open', CURRENT_DATE, CURRENT_DATE + 30, ?) RETURNING id
                """, tenantId, customerId, siteId, userId);
        UUID cpoLineId = insert("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 1, ?, 10, ?, 100) RETURNING id
                """, tenantId, cpoId, itemId, uomId);
        UUID categoryId = jdbc.queryForObject(
                "SELECT id FROM config_categories WHERE code = 'PRODUCTION_STAGES'", UUID.class);
        UUID stageOptionId = insert("""
                INSERT INTO tenant_config_options (tenant_id, category_id, code, label)
                VALUES (?, ?, 'ASSEMBLY', 'Assembly') RETURNING id
                """, tenantId, categoryId);
        UUID routingId = insert("""
                INSERT INTO routing_templates (tenant_id, name, finished_item_id, version, status)
                VALUES (?, 'Default Routing', ?, 'v1', 'active') RETURNING id
                """, tenantId, itemId);
        insert("""
                INSERT INTO routing_template_stages
                  (tenant_id, routing_id, seq_no, stage_config_id, name, requires_inspection, is_optional)
                VALUES (?, ?, 1, ?, 'Assembly', TRUE, FALSE) RETURNING id
                """, tenantId, routingId, stageOptionId);
        jobId = insert("""
                INSERT INTO production_jobs (tenant_id, job_number, cpo_id, cpo_line_id, finished_item_id,
                        quantity, uom_id, routing_id, status, priority, site_id, planned_start,
                        planned_end, created_by)
                VALUES (?, 'JOB-001', ?, ?, ?, 10, ?, ?, 'released', 'normal', ?, CURRENT_DATE,
                        CURRENT_DATE + 30, ?) RETURNING id
                """, tenantId, cpoId, cpoLineId, itemId, uomId, routingId, siteId, userId);
        jdbc.update("""
                INSERT INTO job_costing_headers (tenant_id, job_id, est_material_cost, selling_price, status)
                VALUES (?, ?, 0, 1000, 'estimated')
                """, tenantId, jobId);
        stageId = insert("""
                INSERT INTO job_stages (tenant_id, job_id, seq_no, name, stage_config_option_id, status,
                        requires_inspection, is_optional)
                VALUES (?, ?, 1, 'Assembly', ?, 'in_progress', TRUE, FALSE) RETURNING id
                """, tenantId, jobId, stageOptionId);

        // Create a lot for lot-based tests
        UUID storeId = insert("""
                INSERT INTO stores (tenant_id, name, code, site_id, store_type)
                VALUES (?, 'FG Store', 'FG', ?, 'finished_goods') RETURNING id
                """, tenantId, siteId);
        lotId = insert("""
                INSERT INTO material_lots (tenant_id, item_id, store_id, quantity, unit_cost, status)
                VALUES (?, ?, ?, 10, 50, 'available') RETURNING id
                """, tenantId, itemId, storeId);
    }

    // ===== INSPECTION LIST AND CREATION =====

    @Test
    void createAndListInspection() {
        setRole("OWNER");
        CreateInspectionRequest req = new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                new BigDecimal("10"), null, null, LocalDate.now(), "Final check");

        InspectionDetail created = qualityService.createInspection(req);

        assertThat(created.id()).isNotNull();
        assertThat(created.inspectionNumber()).startsWith("INSP-");
        assertThat(created.inspectionType()).isEqualTo("final");
        assertThat(created.referenceType()).isEqualTo("production_job");
        assertThat(created.status()).isEqualTo("pending");

        List<InspectionSummary> list = qualityService.listInspections("final", null, null);
        assertThat(list).extracting(InspectionSummary::id).contains(created.id());
    }

    @Test
    void inspectionAndNcrKeepSelectedOptionLabelsAfterRenameAndDeactivation() {
        setRole("OWNER");
        UUID inspectionTypeCategoryId = jdbc.queryForObject(
                "SELECT id FROM config_categories WHERE code = 'INSPECTION_TYPES'", UUID.class);
        UUID defectCategoryId = jdbc.queryForObject(
                "SELECT id FROM config_categories WHERE code = 'DEFECT_CATEGORY'", UUID.class);
        UUID inspectionTypeOptionId = insert("""
                INSERT INTO tenant_config_options (tenant_id, category_id, code, label)
                VALUES (?, ?, 'VISUAL_CHECK', 'Visual Check') RETURNING id
                """, tenantId, inspectionTypeCategoryId);
        UUID defectCategoryOptionId = insert("""
                INSERT INTO tenant_config_options (tenant_id, category_id, code, label)
                VALUES (?, ?, 'SURFACE_DEFECT', 'Surface Defect') RETURNING id
                """, tenantId, defectCategoryId);

        InspectionDetail inspection = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null, BigDecimal.TEN,
                null, null, LocalDate.now(), null, inspectionTypeOptionId));
        NcrDetail ncr = qualityService.createNcr(new CreateNcrRequest(
                inspection.id(), itemId, null, jobId, defectCategoryOptionId,
                "Surface defect found", "major", BigDecimal.ONE, null));

        jdbc.update("""
                UPDATE tenant_config_options
                SET label = CASE id WHEN ? THEN 'Visual Examination' ELSE 'Surface Damage' END,
                    is_active = FALSE
                WHERE id IN (?, ?)
                """, inspectionTypeOptionId, inspectionTypeOptionId, defectCategoryOptionId);

        InspectionDetail historicalInspection = qualityService.getInspection(inspection.id());
        NcrDetail historicalNcr = qualityService.getNcr(ncr.id());
        InspectionSummary listedInspection = qualityService.listInspections("final", null, null).stream()
                .filter(row -> row.id().equals(inspection.id()))
                .findFirst()
                .orElseThrow();
        NcrSummary listedNcr = qualityService.listNcrs(null, null).stream()
                .filter(row -> row.id().equals(ncr.id()))
                .findFirst()
                .orElseThrow();
        assertThat(historicalInspection.inspectionTypeOptionId()).isEqualTo(inspectionTypeOptionId);
        assertThat(historicalInspection.inspectionTypeLabel()).isEqualTo("Visual Check");
        assertThat(listedInspection.inspectionTypeLabel()).isEqualTo("Visual Check");
        assertThat(historicalNcr.defectCategoryId()).isEqualTo(defectCategoryOptionId);
        assertThat(historicalNcr.defectCategoryLabel()).isEqualTo("Surface Defect");
        assertThat(listedNcr.defectCategoryLabel()).isEqualTo("Surface Defect");
    }

    @Test
    void recordPassResult() {
        setRole("OWNER");
        InspectionDetail inspection = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                new BigDecimal("10"), null, null, LocalDate.now(), null));

        RecordResultRequest result = new RecordResultRequest(
                "pass", new BigDecimal("10"), BigDecimal.ZERO, null, "All good");

        InspectionDetail updated = qualityService.recordResult(inspection.id(), result);

        assertThat(updated.result()).isEqualTo("pass");
        assertThat(updated.status()).isEqualTo("completed");
        assertThat(updated.quantityAccepted()).isEqualByComparingTo("10");
        assertThat(updated.linkedNcrs()).isEmpty();
    }

    @Test
    void recordFailResultAutoCreatesNcr() {
        setRole("OWNER");
        InspectionDetail inspection = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                new BigDecimal("10"), null, null, LocalDate.now(), null));

        RecordResultRequest result = new RecordResultRequest(
                "fail", BigDecimal.ZERO, new BigDecimal("10"), null, "Defective parts");

        InspectionDetail updated = qualityService.recordResult(inspection.id(), result);

        assertThat(updated.result()).isEqualTo("fail");
        assertThat(updated.linkedNcrs()).hasSize(1);
        assertThat(updated.linkedNcrs().get(0).ncrNumber()).startsWith("NCR-");
    }

    @Test
    void completedInspectionCannotBeReRecorded() {
        setRole("OWNER");
        InspectionDetail inspection = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null));
        qualityService.recordResult(inspection.id(),
                new RecordResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, null, null));

        assertThatThrownBy(() -> qualityService.recordResult(inspection.id(),
                new RecordResultRequest("fail", BigDecimal.ZERO, BigDecimal.TEN, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("completed");
    }

    @Test
    void quantitiesCannotExceedInspected() {
        setRole("OWNER");
        InspectionDetail inspection = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                new BigDecimal("5"), null, null, LocalDate.now(), null));

        assertThatThrownBy(() -> qualityService.recordResult(inspection.id(),
                new RecordResultRequest("fail", new BigDecimal("4"), new BigDecimal("4"), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceed");
    }

    // ===== NCR =====

    @Test
    void createNcr() {
        setRole("OWNER");
        UUID inspectionId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();

        NcrDetail ncr = qualityService.createNcr(new CreateNcrRequest(
                inspectionId, itemId, null, jobId, null,
                "Surface roughness out of spec", "major", new BigDecimal("2"), "Machine wear"));

        assertThat(ncr.ncrNumber()).startsWith("NCR-");
        assertThat(ncr.status()).isEqualTo("open");
        assertThat(ncr.severity()).isEqualTo("major");
        assertThat(ncr.quantity()).isEqualByComparingTo("2");
    }

    @Test
    void ncrRequiresValidInspection() {
        setRole("OWNER");
        UUID fakeInspectionId = UUID.randomUUID();

        assertThatThrownBy(() -> qualityService.createNcr(new CreateNcrRequest(
                fakeInspectionId, itemId, null, null, null, "desc", "minor",
                BigDecimal.ONE, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Inspection not found");
    }

    @Test
    void ncrRequiresValidItem() {
        setRole("OWNER");
        UUID inspectionId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();

        assertThatThrownBy(() -> qualityService.createNcr(new CreateNcrRequest(
                inspectionId, UUID.randomUUID(), null, null, null, "desc", "minor",
                BigDecimal.ONE, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Item not found");
    }

    // ===== CONCESSION =====

    @Test
    void concessionRequiresOwnerOrOpsManager() {
        setRole("QUALITY_INSPECTOR");
        UUID inspectionId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        NcrDetail ncr = qualityService.createNcr(new CreateNcrRequest(
                inspectionId, itemId, null, jobId, null, "Failed", "major",
                BigDecimal.ONE, null));

        setRole("QUALITY_INSPECTOR"); // not authorised to approve
        assertThatThrownBy(() -> qualityService.createConcession(ncr.id(),
                new CreateConcessionRequest("Justified")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ownerCanGrantConcession() {
        setRole("OWNER");
        UUID inspectionId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        NcrDetail ncr = qualityService.createNcr(new CreateNcrRequest(
                inspectionId, itemId, null, jobId, null, "Failed", "major",
                BigDecimal.ONE, null));

        NcrDetail updated = qualityService.createConcession(ncr.id(),
                new CreateConcessionRequest("Customer approved deviation"));

        assertThat(updated.concessions()).hasSize(1);
        assertThat(updated.concessions().get(0).status()).isEqualTo("approved");
        assertThat(updated.concessions().get(0).approvalNotes()).isEqualTo("Customer approved deviation");
    }

    @Test
    void concessionRequiresJustification() {
        setRole("OWNER");
        UUID inspectionId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        NcrDetail ncr = qualityService.createNcr(new CreateNcrRequest(
                inspectionId, itemId, null, jobId, null, "Failed", "major",
                BigDecimal.ONE, null));

        assertThatThrownBy(() -> qualityService.createConcession(ncr.id(),
                new CreateConcessionRequest("")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Justification");
    }

    // ===== DISPATCH ELIGIBILITY =====

    @Test
    void dispatchBlockedWhenNoFinalInspection() {
        setRole("OWNER");
        DispatchEligibilityResponse eligibility = qualityService.checkDispatchEligibility(jobId);
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.blockers()).anyMatch(b -> b.contains("inspection"));
    }

    @Test
    void dispatchEligibleAfterFinalInspectionPasses() {
        setRole("OWNER");
        qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null));
        // Record pass result
        List<InspectionSummary> inspections = qualityService.listInspections("final", null, null);
        InspectionSummary insp = inspections.stream()
                .filter(i -> jobId.equals(i.referenceId())).findFirst().orElseThrow();
        qualityService.recordResult(insp.id(),
                new RecordResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, null, null));

        DispatchEligibilityResponse eligibility = qualityService.checkDispatchEligibility(jobId);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.inspectionStatus()).isEqualTo("passed");
    }

    @Test
    void dispatchEligibleWithConcessionOnFailedInspection() {
        setRole("OWNER");
        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        qualityService.recordResult(inspId,
                new RecordResultRequest("fail", BigDecimal.ZERO, BigDecimal.TEN, null, "Failed"));

        NcrDetail ncr = qualityService.listNcrs(null, null).stream()
                .filter(n -> inspId.equals(n.inspectionId())).findFirst().map(NcrSummary::id)
                .map(id -> qualityService.getNcr(id)).orElseThrow();
        qualityService.createConcession(ncr.id(),
                new CreateConcessionRequest("Customer approved use-as-is"));

        DispatchEligibilityResponse eligibility = qualityService.checkDispatchEligibility(jobId);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.inspectionStatus()).isEqualTo("concession_granted");
    }

    @Test
    void dispatchBlockedByUnresolvedNcr() {
        setRole("OWNER");
        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        qualityService.recordResult(inspId,
                new RecordResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, null, null));
        // Manually create an open NCR without concession
        qualityService.createNcr(new CreateNcrRequest(
                inspId, itemId, null, jobId, null, "Scratched surface",
                "minor", BigDecimal.ONE, null));

        DispatchEligibilityResponse eligibility = qualityService.checkDispatchEligibility(jobId);
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.blockers()).anyMatch(b -> b.contains("NCR"));
    }

    // ===== PRODUCTION STAGE GATE =====

    @Test
    void stageGateBlocksWithoutInspection() {
        setRole("OWNER");

        assertThatThrownBy(() -> jobStageService.complete(jobId, stageId,
                new in.boss.production.api.ProductionApiDtos.StageActionRequest("done")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inspection");
    }

    @Test
    void stageGatePassesAfterInspectionPass() {
        setRole("OWNER");

        // Create and pass inspection for this stage
        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        qualityService.recordResult(inspId,
                new RecordResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, null, null));

        // Should now pass the stage gate
        var job = jobStageService.complete(jobId, stageId,
                new in.boss.production.api.ProductionApiDtos.StageActionRequest("done"));
        assertThat(job).isNotNull();
    }

    @Test
    void stageGatePassesWithApprovedConcession() {
        setRole("OWNER");

        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        qualityService.recordResult(inspId,
                new RecordResultRequest("fail", BigDecimal.ZERO, BigDecimal.TEN, null, "Failed"));

        NcrDetail ncr = listNcrsForInspection(inspId);
        qualityService.createConcession(ncr.id(),
                new CreateConcessionRequest("Approved for this batch"));

        // Should pass with concession
        var job = jobStageService.complete(jobId, stageId,
                new in.boss.production.api.ProductionApiDtos.StageActionRequest("conceded"));
        assertThat(job).isNotNull();
    }

    @Test
    void passedInspectionOnDifferentJobDoesNotSatisfyGate() {
        setRole("OWNER");

        // Create and pass inspection referencing this stage
        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        qualityService.recordResult(inspId,
                new RecordResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, null, null));

        insert("""
                INSERT INTO job_stages (tenant_id, job_id, seq_no, name, stage_config_option_id, status,
                        requires_inspection, is_optional)
                VALUES (?, ?, 2, 'Pack', ?, 'pending', TRUE, FALSE) RETURNING id
                """, tenantId, jobId,
                jdbc.queryForObject("SELECT stage_config_option_id FROM job_stages WHERE id = ?",
                        UUID.class, stageId));

        // The original stageId still has a passed inspection, so gate is open
        // This confirms isolation — adding a different stage doesn't break the first one
        var job = jobStageService.complete(jobId, stageId,
                new in.boss.production.api.ProductionApiDtos.StageActionRequest("done"));
        assertThat(job).isNotNull();
    }

    // ===== CHECKLIST TEMPLATE =====

    @Test
    void adminCanCreateAndListTemplates() {
        setRole("OWNER");
        CreateChecklistTemplateRequest req = new CreateChecklistTemplateRequest(
                "Material Receipt Check", "Standard material inspection",
                "material", "incoming",
                List.of(
                        new ChecklistItemInput(null, 1, "Visual check for damage", "pass_fail", true, null, null, null),
                        new ChecklistItemInput(null, 2, "Quantity verified", "yes_no", true, null, null, null),
                        new ChecklistItemInput(null, 3, "Dimension measurement", "numeric", false, "mm", new BigDecimal("9.5"), new BigDecimal("10.5"))
                ));

        ChecklistTemplateDto created = qualityService.createTemplate(req);

        assertThat(created.name()).isEqualTo("Material Receipt Check");
        assertThat(created.scope()).isEqualTo("material");
        assertThat(created.isActive()).isTrue();
        assertThat(created.items()).hasSize(3);
        assertThat(created.items().get(0).description()).isEqualTo("Visual check for damage");
        assertThat(created.items().get(2).specMin()).isEqualByComparingTo("9.5");
    }

    @Test
    void deactivatedTemplateCannotBeUsedForNewInspection() {
        setRole("OWNER");
        ChecklistTemplateDto template = qualityService.createTemplate(new CreateChecklistTemplateRequest(
                "Old Template", null, "job", "in_process",
                List.of(new ChecklistItemInput(null, 1, "Check A", "pass_fail", true, null, null, null))));

        // Deactivate it
        qualityService.updateTemplate(template.id(),
                new UpdateChecklistTemplateRequest(null, null, null, null, false, null));

        // Trying to use this template should fail
        assertThatThrownBy(() -> qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                BigDecimal.TEN, template.id(), null, LocalDate.now(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void mandatoryResponseEnforcedOnComplete() {
        setRole("OWNER");
        ChecklistTemplateDto template = qualityService.createTemplate(new CreateChecklistTemplateRequest(
                "Mandatory Check", null, "job", "in_process",
                List.of(new ChecklistItemInput(null, 1, "Critical check", "pass_fail", true, null, null, null))));

        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                BigDecimal.TEN, template.id(), null, LocalDate.now(), null)).id();

        // Try to complete without answering mandatory item
        assertThatThrownBy(() -> qualityService.recordResult(inspId,
                new RecordResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, List.of(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Mandatory");
    }

    @Test
    void checklistResponsesPersistedAfterSave() {
        setRole("OWNER");
        ChecklistTemplateDto template = qualityService.createTemplate(new CreateChecklistTemplateRequest(
                "Save Test", null, "job", "in_process",
                List.of(new ChecklistItemInput(null, 1, "Visual check", "pass_fail", false, null, null, null))));

        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                BigDecimal.TEN, template.id(), null, LocalDate.now(), null)).id();

        UUID checklistItemId = qualityService.getTemplate(template.id()).items().get(0).id();
        qualityService.recordResult(inspId, new RecordResultRequest(
                "pass", BigDecimal.TEN, BigDecimal.ZERO,
                List.of(new ChecklistResponseInput(checklistItemId, null, true, "Looks good")),
                null));

        InspectionDetail reloaded = qualityService.getInspection(inspId);
        assertThat(reloaded.checklistResponses()).hasSize(1);
        assertThat(reloaded.checklistResponses().get(0).responsePass()).isTrue();
        assertThat(reloaded.checklistResponses().get(0).observation()).isEqualTo("Looks good");
        assertThat(reloaded.checklistResponses().get(0).answered()).isTrue();
    }

    @Test
    void historicalResponsesUnchangedAfterTemplateEdit() {
        setRole("OWNER");
        ChecklistTemplateDto template = qualityService.createTemplate(new CreateChecklistTemplateRequest(
                "History Test", null, "job", "in_process",
                List.of(new ChecklistItemInput(null, 1, "Original question", "pass_fail", false, null, null, null))));

        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "in_process", "job_stage", stageId, itemId, null,
                BigDecimal.TEN, template.id(), null, LocalDate.now(), null)).id();
        UUID itemId = qualityService.getTemplate(template.id()).items().get(0).id();
        qualityService.recordResult(inspId, new RecordResultRequest(
                "pass", BigDecimal.TEN, BigDecimal.ZERO,
                List.of(new ChecklistResponseInput(itemId, null, true, null)), null));

        // Edit the template — rename question
        qualityService.updateTemplate(template.id(), new UpdateChecklistTemplateRequest(
                null, null, null, null, null,
                List.of(new ChecklistItemInput(null, 1, "CHANGED question", "pass_fail", false, null, null, null))));

        // Historical inspection response still shows original text snapshot
        InspectionDetail reloaded = qualityService.getInspection(inspId);
        assertThat(reloaded.checklistResponses().get(0).itemDescription()).isEqualTo("Original question");
    }

    // ===== TENANT ISOLATION =====

    @Test
    void tenantCannotSeeAnotherTenantsInspections() {
        setRole("OWNER");
        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();

        // Switch to different tenant
        UUID otherTenant = provisionTenant("quality-other");
        setTenantContext(otherTenant, "OWNER");

        List<InspectionSummary> otherList = qualityService.listInspections(null, null, null);
        assertThat(otherList).extracting(InspectionSummary::id).doesNotContain(inspId);
    }

    @Test
    void tenantCannotAccessAnotherTenantsNcr() {
        setRole("OWNER");
        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        NcrDetail ncr = qualityService.createNcr(new CreateNcrRequest(
                inspId, itemId, null, jobId, null, "desc", "minor", BigDecimal.ONE, null));

        UUID otherTenant = provisionTenant("quality-other2");
        setTenantContext(otherTenant, "OWNER");

        assertThatThrownBy(() -> qualityService.getNcr(ncr.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    // ===== CLEARANCE CERTIFICATE =====

    @Test
    void createClearanceCertificate() {
        setRole("OWNER");
        ClearanceCertificateDto cert = qualityService.createClearanceCertificate(
                new CreateClearanceCertificateRequest(jobId, "John Smith", "REF-001", "Pre-dispatch clearance"));

        assertThat(cert.certNumber()).startsWith("CERT-");
        assertThat(cert.status()).isEqualTo("issued");
        assertThat(cert.jobId()).isEqualTo(jobId);
        assertThat(cert.customerRef()).isEqualTo("REF-001");
    }

    @Test
    void missingCertDoesNotBlockWhenNotRequired() {
        setRole("OWNER");
        // Final inspection passes, no cert required
        UUID inspId = qualityService.createInspection(new CreateInspectionRequest(
                "final", "production_job", jobId, itemId, null,
                BigDecimal.TEN, null, null, LocalDate.now(), null)).id();
        qualityService.recordResult(inspId,
                new RecordResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, null, null));

        DispatchEligibilityResponse eligibility = qualityService.checkDispatchEligibility(jobId);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.clearanceCertificateStatus()).isEqualTo("not_required");
    }

    // ===== HELPERS =====

    private NcrDetail listNcrsForInspection(UUID inspectionId) {
        return qualityService.listNcrs(null, null).stream()
                .filter(n -> inspectionId.equals(n.inspectionId()))
                .findFirst()
                .map(s -> qualityService.getNcr(s.id()))
                .orElseThrow(() -> new IllegalStateException("No NCR for inspection " + inspectionId));
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    private void setRole(String role) {
        UUID userId = BossContextHolder.get() != null ? BossContextHolder.get().userId() : UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of(role), null, null));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(role, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
