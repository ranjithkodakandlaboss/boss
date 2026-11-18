package in.boss.production;

import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.production.api.ProductionApiDtos.AddCostRequest;
import in.boss.production.api.ProductionApiDtos.ConcessionRequest;
import in.boss.production.api.ProductionApiDtos.CreateJobRequest;
import in.boss.production.api.ProductionApiDtos.InspectionResultRequest;
import in.boss.production.api.ProductionApiDtos.IssueMaterialRequest;
import in.boss.production.api.ProductionApiDtos.ProductionJobResponse;
import in.boss.production.api.ProductionApiDtos.ReasonRequest;
import in.boss.production.api.ProductionApiDtos.ReturnMaterialRequest;
import in.boss.production.domain.JobCostingService;
import in.boss.production.domain.JobInspectionService;
import in.boss.production.domain.JobMaterialService;
import in.boss.production.domain.JobStageService;
import in.boss.production.domain.ProductionJobService;
import in.boss.production.domain.ProductionQueryService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class ProductionLifecycleIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired private ProductionJobService jobs;
    @Autowired private ProductionQueryService query;
    @Autowired private JobStageService stages;
    @Autowired private JobMaterialService materials;
    @Autowired private JobCostingService costing;
    @Autowired private JobInspectionService inspections;
    @Autowired private InventoryService inventory;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID siteId;
    private UUID finishedItemId;
    private UUID materialItemId;
    private UUID cpoId;
    private UUID lineId;
    private UUID routingId;
    private UUID stageOptionId;
    private UUID lotId;
    private UUID requirementId;

    @BeforeEach
    void fixtures() {
        tenantId = provisionTenant("prod");
        setTenantContext(tenantId, "OWNER");
        UUID userId = BossContextHolder.get().userId();
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Production Entity', 'Production Entity Ltd') RETURNING id
                """, tenantId);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Plant', ?) RETURNING id
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 6));
        BossContextHolder.set(new BossContext(tenantId, userId, java.util.Set.of("OWNER"), siteId, null));
        UUID uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Each', 'ea', TRUE) RETURNING id
                """, tenantId);
        UUID customerId = insert("""
                INSERT INTO customers (tenant_id, code, name)
                VALUES (?, ?, 'North Works') RETURNING id
                """, tenantId, "CUST-" + UUID.randomUUID().toString().substring(0, 6));
        materialItemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, std_cost)
                VALUES (?, ?, 'Plate', 'raw_material', ?, 2.50) RETURNING id
                """, tenantId, "MAT-" + UUID.randomUUID().toString().substring(0, 6), uomId);
        finishedItemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Frame', 'finished_goods', ?) RETURNING id
                """, tenantId, "FG-" + UUID.randomUUID().toString().substring(0, 6), uomId);
        UUID bomId = insert("""
                INSERT INTO bom_headers (tenant_id, finished_item_id, version, status, created_by)
                VALUES (?, ?, ?, 'approved', ?) RETURNING id
                """, tenantId, finishedItemId, "v-" + UUID.randomUUID().toString().substring(0, 6), userId);
        jdbc.update("""
                INSERT INTO bom_lines (tenant_id, bom_header_id, component_item_id, quantity, uom_id, level)
                VALUES (?, ?, ?, 2, ?, 1)
                """, tenantId, bomId, materialItemId, uomId);
        cpoId = insert("""
                INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, status, order_date, required_by_date, created_by)
                VALUES (?, ?, ?, ?, 'open', CURRENT_DATE, ?, ?) RETURNING id
                """, tenantId, "CPO-" + UUID.randomUUID().toString().substring(0, 6), customerId, siteId,
                LocalDate.now().plusDays(10), userId);
        lineId = insert("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 1, ?, 10, ?, 100) RETURNING id
                """, tenantId, cpoId, finishedItemId, uomId);
        UUID categoryId = jdbc.queryForObject(
                "SELECT id FROM config_categories WHERE code = 'PRODUCTION_STAGES'", UUID.class);
        stageOptionId = insert("""
                INSERT INTO tenant_config_options (tenant_id, category_id, code, label)
                VALUES (?, ?, ?, 'Cutting') RETURNING id
                """, tenantId, categoryId, "CUT-" + UUID.randomUUID().toString().substring(0, 4));
        routingId = insert("""
                INSERT INTO routing_templates (tenant_id, finished_item_id, name, version, is_default, status)
                VALUES (?, ?, 'Frame route', 'v3', TRUE, 'active') RETURNING id
                """, tenantId, finishedItemId);
        jdbc.update("""
                INSERT INTO routing_template_stages
                  (tenant_id, routing_id, stage_config_id, seq_no, name, requires_inspection, is_optional)
                VALUES (?, ?, ?, 1, 'Template cutting', FALSE, FALSE)
                """, tenantId, routingId, stageOptionId);
        jdbc.update("""
                INSERT INTO routing_template_stages
                  (tenant_id, routing_id, seq_no, name, requires_inspection, is_optional)
                VALUES (?, ?, 2, 'Inspect', TRUE, FALSE)
                """, tenantId, routingId);
        jdbc.update("""
                INSERT INTO routing_template_stages
                  (tenant_id, routing_id, seq_no, name, requires_inspection, is_optional)
                VALUES (?, ?, 3, 'Pack', FALSE, TRUE)
                """, tenantId, routingId);
        UUID storeId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Raw', ?, 'raw_material') RETURNING id
                """, tenantId, siteId, "ST-" + UUID.randomUUID().toString().substring(0, 4));
        lotId = inventory.createLot(new CreateLotRequest(
                materialItemId, storeId, new BigDecimal("20"), new BigDecimal("2.50"),
                "BATCH", null, "available", null)).id();
    }

    @Test
    void customerPoBecomesACostedJobWithoutChangingTheSellingPrice() {
        assertThat(query.eligibleLines()).extracting(line -> line.cpoLineId()).contains(lineId);
        assertThat(query.routingTemplates(finishedItemId)).extracting(template -> template.id()).contains(routingId);
        assertThat(query.routingTemplates(null)).isNotEmpty();
        assertThatThrownBy(() -> query.list(null, null, -1, 20)).isInstanceOf(ResponseStatusException.class);
        ProductionJobResponse job = jobs.create(new CreateJobRequest(
                cpoId, lineId, routingId, LocalDate.now(), LocalDate.now().plusDays(10), "high"));
        assertThat(job.status()).isEqualTo("planned");
        assertThat(job.stages()).extracting(stage -> stage.stageName())
                .containsExactly("Cutting", "Inspect", "Pack");
        assertThat(job.stages().get(0).stageCode()).startsWith("CUT-");
        assertThat(job.materials()).hasSize(1);
        assertThat(job.materials().get(0).requiredQty()).isEqualByComparingTo("20.000");
        assertThat(job.estimatedCost().materialCost()).isEqualByComparingTo("50.00");
        assertThat(job.actualCost().sellingPrice()).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject(
                "SELECT unit_price FROM cpo_lines WHERE tenant_id = ? AND id = ?",
                BigDecimal.class, tenantId, lineId)).isEqualByComparingTo("100.00");

        jdbc.update("UPDATE tenant_config_options SET label = 'Sawing' WHERE id = ?", stageOptionId);
        jdbc.update("UPDATE routing_template_stages SET name = 'Changed' WHERE routing_id = ?", routingId);
        assertThat(query.get(job.id()).stages().get(0).stageName()).isEqualTo("Cutting");

        requirementId = job.materials().get(0).id();
        var issued = materials.issue(job.id(), new IssueMaterialRequest(requirementId, lotId, new BigDecimal("20"), "kit"));
        assertThat(issued.movementId()).isNotNull();
        assertThat(query.get(job.id()).status()).isEqualTo("released");
        assertThat(inventory.getLot(lotId).qty()).isEqualByComparingTo("0");
        materials.returnMaterial(job.id(), new ReturnMaterialRequest(issued.id(), new BigDecimal("4"), "unused"));
        assertThat(inventory.getLot(lotId).qty()).isEqualByComparingTo("4");

        var started = stages.start(job.id(), job.stages().get(0).id(), null);
        assertThat(started.status()).isEqualTo("in_progress");
        stages.complete(job.id(), job.stages().get(0).id(), null);
        assertThatThrownBy(() -> stages.complete(job.id(), job.stages().get(1).id(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inspection");
        inspections.open(job.id(), job.stages().get(1).id());
        UUID inspectionId = query.get(job.id()).inspections().get(0).id();
        inspections.record(job.id(), inspectionId, new InspectionResultRequest("pass", BigDecimal.TEN, BigDecimal.ZERO, "ok"));
        stages.complete(job.id(), job.stages().get(1).id(), null);
        costing.addEntry(job.id(), new AddCostRequest("LABOUR", "Shift", new BigDecimal("10.00"), null, null));
        asRole("PRODUCTION_SUPERVISOR");
        assertThatThrownBy(() -> stages.skip(job.id(), job.stages().get(2).id(), new ReasonRequest("not needed")))
                .isInstanceOf(AccessDeniedException.class);
        asRole("OWNER");
        ProductionJobResponse completed = stages.skip(job.id(), job.stages().get(2).id(), new ReasonRequest("not needed"));
        assertThat(completed.status()).isEqualTo("completed");
        var cost = costing.get(job.id());
        assertThat(cost.actualCost().materialCost()).isEqualByComparingTo("40.00");
        assertThat(cost.actualCost().totalCost()).isEqualByComparingTo("50.00");
        assertThat(cost.variance()).isEqualByComparingTo("0.00");
        assertThat(cost.actualCost().marginPct()).isEqualByComparingTo("95.0000");
        assertThat(cost.actualCost().sellingPrice()).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject(
                "SELECT unit_price FROM cpo_lines WHERE id = ?", BigDecimal.class, lineId))
                .isEqualByComparingTo("100.00");
        assertThat(query.businessMetrics().completedJobs()).isEqualTo(1);
        assertThat(query.businessMetrics().materialConsumedQty()).isEqualByComparingTo("16");
        assertThat(query.list(cpoId, "completed", 0, 20).totalElements()).isEqualTo(1);
        assertThat(query.features()).containsEntry("production.job.create", true);
    }

    @Test
    void failedInspectionBlocksTheStageUntilAnOwnerConcession() {
        ProductionJobResponse job = jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null));
        requirementId = job.materials().get(0).id();
        materials.issue(job.id(), new IssueMaterialRequest(requirementId, lotId, new BigDecimal("20"), null));
        stages.start(job.id(), job.stages().get(0).id(), null);
        stages.complete(job.id(), job.stages().get(0).id(), null);
        inspections.open(job.id(), job.stages().get(1).id());
        UUID inspectionId = query.get(job.id()).inspections().get(0).id();
        inspections.record(job.id(), inspectionId, new InspectionResultRequest("fail", BigDecimal.ZERO, BigDecimal.TEN, "crack"));
        assertThat(query.get(job.id()).inspections().get(0).ncrId()).isNotNull();
        assertThatThrownBy(() -> stages.complete(job.id(), job.stages().get(1).id(), null))
                .isInstanceOf(ResponseStatusException.class);
        inspections.concede(job.id(), inspectionId, new ConcessionRequest("use_as_is", "Customer accepted the deviation"));
        stages.complete(job.id(), job.stages().get(1).id(), null);
        assertThat(query.get(job.id()).stages().get(1).inspectionStatus()).isEqualTo("passed");
    }

    @Test
    void ineligibleDuplicateAndDisabledFlagsAreRejected() {
        jdbc.update("UPDATE cpos SET status = 'on_hold' WHERE id = ?", cpoId);
        assertThatThrownBy(() -> jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot start production");
        jdbc.update("UPDATE cpos SET status = 'open' WHERE id = ?", cpoId);
        jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null));
        assertThatThrownBy(() -> jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
        jdbc.update("""
                INSERT INTO tenant_entitlements (tenant_id, feature, enabled)
                VALUES (?, 'production.material.issue', FALSE)
                """, tenantId);
        ProductionJobResponse other = createSecondJob();
        assertThatThrownBy(() -> materials.issue(other.id(), new IssueMaterialRequest(
                other.materials().get(0).id(), lotId, BigDecimal.ONE, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void overIssueRollsBackAndASecondTenantCannotReadTheJob() {
        ProductionJobResponse job = jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null));
        assertThatThrownBy(() -> materials.issue(job.id(), new IssueMaterialRequest(
                job.materials().get(0).id(), lotId, new BigDecimal("21"), null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(inventory.getLot(lotId).qty()).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_material_issues WHERE tenant_id = ? AND job_id = ?",
                Integer.class, tenantId, job.id())).isZero();
        UUID other = provisionTenant("other-prod");
        setTenantContext(other, "OWNER");
        assertThat(query.list(null, null, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> query.get(job.id())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void siteScopedUserSeesOnlyThatSitesJobsAndEligibleOrders() {
        UUID userId = BossContextHolder.get().userId();
        UUID legalEntityId = jdbc.queryForObject(
                "SELECT legal_entity_id FROM physical_sites WHERE id = ?", UUID.class, siteId);
        UUID customerId = jdbc.queryForObject("SELECT customer_id FROM cpos WHERE id = ?", UUID.class, cpoId);
        UUID uomId = jdbc.queryForObject("SELECT uom_id FROM cpo_lines WHERE id = ?", UUID.class, lineId);
        UUID siteB = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Plant B', ?) RETURNING id
                """, tenantId, legalEntityId, "SB-" + UUID.randomUUID().toString().substring(0, 6));
        UUID cpoB = insert("""
                INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, status, order_date, created_by)
                VALUES (?, ?, ?, ?, 'open', CURRENT_DATE, ?) RETURNING id
                """, tenantId, "CPOB-" + UUID.randomUUID().toString().substring(0, 6), customerId, siteB, userId);
        UUID lineB = insert("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 1, ?, 10, ?, 100) RETURNING id
                """, tenantId, cpoB, finishedItemId, uomId);

        scope(siteId);
        assertThat(query.eligibleLines()).extracting(line -> line.cpoLineId()).contains(lineId).doesNotContain(lineB);
        ProductionJobResponse jobA = jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null));

        scope(siteB);
        assertThat(query.list(null, null, 0, 20).content()).extracting(job -> job.id()).doesNotContain(jobA.id());
        assertThatThrownBy(() -> query.get(jobA.id())).isInstanceOf(ResponseStatusException.class);
        assertThat(query.eligibleLines()).extracting(line -> line.cpoLineId()).containsExactly(lineB);
        ProductionJobResponse jobB = jobs.create(new CreateJobRequest(cpoB, lineB, routingId, null, null, null));
        assertThat(query.list(null, null, 0, 20).content()).extracting(job -> job.id()).containsExactly(jobB.id());

        scope(siteId);
        assertThat(query.list(null, null, 0, 20).content()).extracting(job -> job.id()).containsExactly(jobA.id());
        assertThatThrownBy(() -> query.get(jobB.id())).isInstanceOf(ResponseStatusException.class);

        scope(null);
        assertThat(query.list(null, null, 0, 20).content()).extracting(job -> job.id()).contains(jobA.id(), jobB.id());
    }

    @Test
    void holdAndReleaseKeepTheJobAndAPlannedJobCanBeCancelled() {
        ProductionJobResponse job = jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null));
        materials.issue(job.id(), new IssueMaterialRequest(job.materials().get(0).id(), lotId, new BigDecimal("20"), null));
        stages.start(job.id(), job.stages().get(0).id(), null);
        assertThat(jobs.hold(job.id(), new ReasonRequest("tooling")).status()).isEqualTo("on_hold");
        assertThat(jobs.releaseHold(job.id()).status()).isEqualTo("in_progress");
        ProductionJobResponse cancelled = jobs.cancel(createSecondJob().id(), new ReasonRequest("customer stopped the order"));
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(query.get(cancelled.id()).risk()).isEqualTo("complete");
    }

    @Test
    void storeKeeperCannotCreateAJobAndConcurrentIssuesCannotOversellTheLot() throws Exception {
        asRole("STORE_KEEPER");
        assertThatThrownBy(() -> jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        asRole("OWNER");
        ProductionJobResponse job = jobs.create(new CreateJobRequest(cpoId, lineId, routingId, null, null, null));
        UUID requirement = job.materials().get(0).id();
        BossContext boss = BossContextHolder.get();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        Future<?> first = pool.submit(() -> issueConcurrent(boss, start, job.id(), requirement, wins));
        Future<?> second = pool.submit(() -> issueConcurrent(boss, start, job.id(), requirement, wins));
        start.countDown();
        first.get();
        second.get();
        pool.shutdown();
        BossContextHolder.set(boss);
        assertThat(wins.get()).isEqualTo(1);
        assertThat(inventory.getLot(lotId).qty()).isEqualByComparingTo("5.000");
    }

    @Test
    void repeatedCreateWithTheSameIdempotencyKeyReturnsTheOriginalJob() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateJobRequest(cpoId, lineId, routingId, null, null, null));
        String key = "job-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/jobs")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        setTenantContext(tenantId, "OWNER");
        BossContext current = BossContextHolder.get();
        BossContextHolder.set(new BossContext(tenantId, current.userId(), java.util.Set.of("OWNER"), siteId, null));
        mockMvc.perform(post("/api/v1/jobs")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_jobs WHERE tenant_id = ? AND cpo_line_id = ?",
                Integer.class, tenantId, lineId)).isEqualTo(1);
    }

    private void issueConcurrent(BossContext boss, CountDownLatch start, UUID jobId, UUID requirement, AtomicInteger wins) {
        BossContextHolder.set(boss);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        try {
            start.await();
            materials.issue(jobId, new IssueMaterialRequest(requirement, lotId, new BigDecimal("15"), "parallel"));
            wins.incrementAndGet();
        } catch (RuntimeException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            BossContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private ProductionJobResponse createSecondJob() {
        UUID userId = BossContextHolder.get().userId();
        UUID secondCpo = insert("""
                INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, status, order_date, created_by)
                SELECT ?, ?, customer_id, site_id, 'open', CURRENT_DATE, ? FROM cpos WHERE id = ? RETURNING id
                """, tenantId, "CPO2-" + UUID.randomUUID().toString().substring(0, 4), userId, cpoId);
        UUID secondLine = insert("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                SELECT tenant_id, ?, 1, item_id, quantity, uom_id, unit_price FROM cpo_lines WHERE id = ? RETURNING id
                """, secondCpo, lineId);
        return jobs.create(new CreateJobRequest(secondCpo, secondLine, routingId, null, null, null));
    }

    private void scope(UUID site) {
        BossContext current = BossContextHolder.get();
        BossContextHolder.set(new BossContext(current.tenantId(), current.userId(), current.roles(), site, current.firebaseUid()));
    }

    private void asRole(String role) {
        BossContext current = BossContextHolder.get();
        BossContextHolder.set(new BossContext(current.tenantId(), current.userId(), java.util.Set.of(role), current.siteId(), null));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                role, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }
}
