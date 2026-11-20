package in.boss.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateTransferRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationRequirement;
import in.boss.inventory.api.InventoryApiDtos.IssueMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.ReservationRequirement;
import in.boss.inventory.api.InventoryApiDtos.ReorderPolicyRequest;
import in.boss.inventory.api.InventoryApiDtos.ReceiveTransferRequest;
import in.boss.inventory.api.InventoryApiDtos.ReturnMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.TransferLineRequest;
import in.boss.inventory.domain.InventoryService;
import in.boss.inventory.domain.InventoryReplenishmentService;
import in.boss.inventory.domain.InventoryTransferService;
import in.boss.inventory.domain.InventoryValidationException;
import in.boss.inventory.domain.InvalidLotTransitionException;
import in.boss.inventory.domain.ReservationConflictException;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InventoryReservationsTest extends InventoryIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryTransferService transferService;

    @Autowired
    private InventoryReplenishmentService replenishmentService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    private Seed seed;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        seed = seed("inventory-reservation");
        asKeeper();
        jobId = insert("""
                INSERT INTO production_jobs
                  (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by)
                VALUES (?, ?, ?, ?, 10, ?, ?) RETURNING id
                """, seed.tenantId(), "JOB-" + UUID.randomUUID().toString().substring(0, 8),
                seed.itemId(), seed.siteId(), seed.uomId(), UUID.randomUUID());
    }

    @Test
    void partialReservationAllocatesAvailableLotsAndReleaseRestoresStock() {
        asKeeper();
        inventoryService.createLot(lotRequest("4"));
        inventoryService.createLot(lotRequest("3"));

        var result = inventoryService.reserveForJob(
                reservationRequest("10"), true);

        assertThat(result.status()).isEqualTo("PARTIALLY_RESERVED");
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.reserved()).isEqualByComparingTo("7.000");
            assertThat(line.shortfall()).isEqualByComparingTo("3.000");
            assertThat(line.allocations()).hasSize(2);
        });
        assertThat(jdbc.queryForObject("""
                SELECT SUM(reserved_qty) FROM material_lots WHERE tenant_id = ?
                """, BigDecimal.class, seed.tenantId())).isEqualByComparingTo("7.000");

        inventoryService.releaseJobReservations(jobId);

        assertThat(jdbc.queryForObject("""
                SELECT SUM(reserved_qty) FROM material_lots WHERE tenant_id = ?
                """, BigDecimal.class, seed.tenantId())).isEqualByComparingTo("0.000");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_id = ? AND status = 'released'
                """, Integer.class, seed.tenantId(), jobId)).isEqualTo(2);
    }

    @Test
    void fullReservationIsAllOrNothingAndDuplicateJobReservationsAreRejected() {
        asKeeper();
        inventoryService.createLot(lotRequest("3"));

        var failed = inventoryService.reserveForJob(reservationRequest("5"), false);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM material_lots WHERE tenant_id = ?
                """, BigDecimal.class, seed.tenantId())).isEqualByComparingTo("0.000");

        var reserved = inventoryService.reserveForJob(reservationRequest("3"), false);
        assertThat(reserved.status()).isEqualTo("FULLY_RESERVED");
        assertThatThrownBy(() -> inventoryService.reserveForJob(reservationRequest("3"), false))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_id = ? AND status = 'active'
                """, Integer.class, seed.tenantId(), jobId)).isEqualTo(1);
    }

    @Test
    void disabledReservationEntitlementRejectsWritesWithoutChangingStock() throws Exception {
        asKeeper();
        inventoryService.createLot(lotRequest("3"));
        insert("""
                INSERT INTO tenant_entitlements (tenant_id, feature, enabled)
                VALUES (?, ?, false) RETURNING id
                """, seed.tenantId(), "inventory.job.reservations");

        mockMvc.perform(get("/api/v1/inventory/features")
                        .with(user("keeper").roles("STORE_KEEPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['inventory.job.reservations']").value(false))
                .andExpect(jsonPath("$['inventory.customer_po.reservations']").value(true))
                .andExpect(jsonPath("$['inventory.transfers']").value(true));

        asKeeper();
        assertThatThrownBy(() -> inventoryService.reserveForJob(reservationRequest("2"), false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Inventory capability is disabled");
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM material_lots WHERE tenant_id = ?
                """, BigDecimal.class, seed.tenantId())).isEqualByComparingTo("0.000");
    }

    @Test
    void disabledMaterialIssueEntitlementPreservesReservationAndLotBalance() {
        asKeeper();
        var lot = inventoryService.createLot(lotRequest("3"));
        inventoryService.reserveForJob(reservationRequest("2"), false);
        insert("""
                INSERT INTO tenant_entitlements (tenant_id, feature, enabled)
                VALUES (?, ?, false) RETURNING id
                """, seed.tenantId(), "inventory.material.issue");

        assertThatThrownBy(() -> inventoryService.issueMaterial(new IssueMaterialRequest(
                jobId, lot.id(), BigDecimal.ONE, "issue")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Inventory capability is disabled");
        assertThat(inventoryService.getLot(lot.id()).qty()).isEqualByComparingTo("3.000");
        assertThat(inventoryService.getLot(lot.id()).reservedQty()).isEqualByComparingTo("2.000");

        jdbc.update("""
                UPDATE tenant_entitlements SET enabled = true
                 WHERE tenant_id = ? AND feature = 'inventory.material.issue'
                """, seed.tenantId());
        inventoryService.issueMaterial(new IssueMaterialRequest(jobId, lot.id(), BigDecimal.ONE, "issue"));
        insert("""
                INSERT INTO tenant_entitlements (tenant_id, feature, enabled)
                VALUES (?, ?, false) RETURNING id
                """, seed.tenantId(), "inventory.material.return");
        assertThatThrownBy(() -> inventoryService.returnMaterial(new ReturnMaterialRequest(
                jobId, lot.id(), BigDecimal.ONE, "return")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Inventory capability is disabled");
        assertThat(inventoryService.getLot(lot.id()).qty()).isEqualByComparingTo("2.000");
        assertThat(inventoryService.getLot(lot.id()).reservedQty()).isEqualByComparingTo("1.000");
    }

    @Test
    void disabledReplenishmentEntitlementPreventsAutomaticRequests() {
        asKeeper();
        inventoryService.createLot(lotRequest("10"));
        replenishmentService.upsertPolicy(new ReorderPolicyRequest(
                seed.itemId(), seed.storeId(), new BigDecimal("9"), new BigDecimal("20"),
                new BigDecimal("30"), 5, null, true));
        insert("""
                INSERT INTO tenant_entitlements (tenant_id, feature, enabled)
                VALUES (?, ?, false) RETURNING id
                """, seed.tenantId(), "inventory.replenishment");

        inventoryService.reserveForJob(reservationRequest("2"), false);

        assertThat(replenishmentService.listRequests(
                null, seed.storeId(), seed.itemId(), 0, 20).content()).isEmpty();
    }

    @Test
    void jobRequirementReservationUsesAllEligibleStoresAtTheJobSiteAtomically() throws Exception {
        asKeeper();
        UUID secondStoreId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Overflow Raw Store', ?, 'raw_material') RETURNING id
                """, seed.tenantId(), seed.siteId(), "RM-" + UUID.randomUUID().toString().substring(0, 8));
        insert("""
                INSERT INTO job_material_requirements
                  (tenant_id, job_id, item_id, required_qty, uom_id)
                VALUES (?, ?, ?, 5, ?) RETURNING id
                """, seed.tenantId(), jobId, seed.itemId(), seed.uomId());
        inventoryService.createLot(lotRequest("2"));
        inventoryService.createLot(new CreateLotRequest(seed.itemId(), secondStoreId,
                new BigDecimal("2"), BigDecimal.ONE, null, null, null, null));

        mockMvc.perform(post("/api/v1/inventory/reservations/{jobId}/requirements", jobId)
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILED"));
        asKeeper();
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM material_lots
                 WHERE tenant_id = ? AND item_id = ?
                """, BigDecimal.class, seed.tenantId(), seed.itemId())).isEqualByComparingTo("0.000");

        inventoryService.createLot(new CreateLotRequest(seed.itemId(), secondStoreId,
                new BigDecimal("3"), BigDecimal.ONE, null, null, null, null));
        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/reservations/{jobId}/requirements", jobId)
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULLY_RESERVED"))
                .andExpect(jsonPath("$.lines[0].storeId").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].requested").value(5.0))
                .andExpect(jsonPath("$.lines[0].reserved").value(5.0))
                .andExpect(jsonPath("$.lines[0].allocations.length()").value(3));
        asKeeper();
        assertThat(jdbc.queryForObject("""
                SELECT SUM(reserved_qty) FROM material_lots
                 WHERE tenant_id = ? AND item_id = ?
                """, BigDecimal.class, seed.tenantId(), seed.itemId())).isEqualByComparingTo("5.000");
    }

    @Test
    void missingJobRequirementsAreSnapshottedFromApprovedBomBeforeAllOrNothingReservation() {
        asKeeper();
        UUID materialItemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'BOM Component', 'raw_material', ?) RETURNING id
                """, seed.tenantId(), "BOM-" + UUID.randomUUID().toString().substring(0, 8),
                seed.uomId());
        UUID bomId = insert("""
                INSERT INTO bom_headers
                  (tenant_id, finished_item_id, version, status)
                VALUES (?, ?, ?, 'approved') RETURNING id
                """, seed.tenantId(), seed.itemId(),
                "v-" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO bom_lines
                  (tenant_id, bom_header_id, component_item_id, quantity, uom_id, scrap_percent)
                VALUES (?, ?, ?, 2, ?, 10)
                """, seed.tenantId(), bomId, materialItemId, seed.uomId());
        inventoryService.createLot(new CreateLotRequest(materialItemId, seed.storeId(),
                new BigDecimal("20"), BigDecimal.ONE, null, null, null, null));

        var insufficient = inventoryService.reserveForJobRequirements(jobId, false);
        assertThat(insufficient.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT required_qty FROM job_material_requirements
                 WHERE tenant_id = ? AND job_id = ? AND item_id = ?
                """, BigDecimal.class, seed.tenantId(), jobId, materialItemId))
                .isEqualByComparingTo("22.000");
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM material_lots
                 WHERE tenant_id = ? AND item_id = ?
                """, BigDecimal.class, seed.tenantId(), materialItemId))
                .isEqualByComparingTo("0.000");

        inventoryService.createLot(new CreateLotRequest(materialItemId, seed.storeId(),
                new BigDecimal("2"), BigDecimal.ONE, null, null, null, null));
        var reserved = inventoryService.reserveForJobRequirements(jobId, false);
        assertThat(reserved.status()).isEqualTo("FULLY_RESERVED");
        assertThat(reserved.lines()).singleElement().satisfies(line -> {
            assertThat(line.itemId()).isEqualTo(materialItemId);
            assertThat(line.requested()).isEqualByComparingTo("22.000");
            assertThat(line.reserved()).isEqualByComparingTo("22.000");
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_material_requirements
                 WHERE tenant_id = ? AND job_id = ?
                """, Integer.class, seed.tenantId(), jobId)).isEqualTo(1);
    }

    @Test
    void duplicateItemRequirementsAreAggregatedAndReservedStockCannotBeQuarantined() {
        asKeeper();
        var lot = inventoryService.createLot(lotRequest("5"));
        var result = inventoryService.reserveForJob(new ReservationRequest(jobId, List.of(
                new ReservationRequirement(seed.itemId(), seed.storeId(), new BigDecimal("2")),
                new ReservationRequirement(seed.itemId(), seed.storeId(), new BigDecimal("2")))), false);

        assertThat(result.status()).isEqualTo("FULLY_RESERVED");
        assertThat(result.lines()).singleElement()
                .extracting(line -> line.reserved())
                .isEqualTo(new BigDecimal("4.000"));
        assertThatThrownBy(() -> inventoryService.transitionLotStatus(lot.id(), "quarantine"))
                .isInstanceOf(InvalidLotTransitionException.class);
    }

    @Test
    void reservationEndpointRequiresIdempotencyAndReplaysWithoutDuplicatingStock() throws Exception {
        asKeeper();
        inventoryService.createLot(lotRequest("3"));
        String body = objectMapper.writeValueAsString(reservationRequest("2"));
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULLY_RESERVED"));

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replayed", "true"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_id = ? AND status = 'active'
                """, Integer.class, seed.tenantId(), jobId)).isEqualTo(1);
    }

    @Test
    void jobIssueAndReturnUpdateLotLedgerAndPreventOverIssueOrReturn() {
        asKeeper();
        var lot = inventoryService.createLot(lotRequest("5"));
        inventoryService.reserveForJob(reservationRequest("4"), false);

        inventoryService.issueMaterial(new IssueMaterialRequest(
                jobId, lot.id(), new BigDecimal("2"), "job issue"));
        assertThat(inventoryService.getLot(lot.id()).qty()).isEqualByComparingTo("3.000");
        assertThat(inventoryService.getLot(lot.id()).reservedQty()).isEqualByComparingTo("2.000");
        assertThatThrownBy(() -> inventoryService.issueMaterial(new IssueMaterialRequest(
                jobId, lot.id(), new BigDecimal("3"), null)))
                .isInstanceOf(InventoryValidationException.class);

        inventoryService.returnMaterial(new ReturnMaterialRequest(
                jobId, lot.id(), BigDecimal.ONE, "unused"));
        assertThat(inventoryService.getLot(lot.id()).qty()).isEqualByComparingTo("4.000");
        assertThat(inventoryService.getLot(lot.id()).reservedQty()).isEqualByComparingTo("2.000");
        assertThatThrownBy(() -> inventoryService.returnMaterial(new ReturnMaterialRequest(
                jobId, lot.id(), new BigDecimal("2"), null)))
                .isInstanceOf(InventoryValidationException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                 WHERE tenant_id = ? AND reference_type = 'JOB' AND reference_id = ?
                """, Integer.class, seed.tenantId(), jobId)).isEqualTo(2);
    }

    @Test
    void productionIssueConsumesJobReservationWithoutDuplicatingItsIssueRecord() {
        asKeeper();
        var lot = inventoryService.createLot(lotRequest("5"));
        inventoryService.reserveForJob(reservationRequest("5"), false);

        inventoryService.issueReservedMaterial(
                new IssueMaterialRequest(jobId, lot.id(), new BigDecimal("2"), "production issue"));

        assertThat(inventoryService.getLot(lot.id()).qty()).isEqualByComparingTo("3.000");
        assertThat(inventoryService.getLot(lot.id()).reservedQty()).isEqualByComparingTo("3.000");
        assertThat(jdbc.queryForObject("""
                SELECT reserved_qty FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_id = ? AND lot_id = ? AND status = 'active'
                """, BigDecimal.class, seed.tenantId(), jobId, lot.id()))
                .isEqualByComparingTo("3.000");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_material_issues
                 WHERE tenant_id = ? AND job_id = ?
                """, Integer.class, seed.tenantId(), jobId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                 WHERE tenant_id = ? AND reference_type = 'JOB' AND reference_id = ?
                   AND movement_type = 'issue'
                """, Integer.class, seed.tenantId(), jobId)).isEqualTo(1);
    }

    @Test
    void lotReservationListShowsOnlyTheCurrentJobAndIssueStillRequiresThatReservation() throws Exception {
        asKeeper();
        var currentLot = inventoryService.createLot(lotRequest("4"));
        inventoryService.reserveForJob(new ReservationRequest(jobId, List.of(
                new ReservationRequirement(seed.itemId(), seed.storeId(), new BigDecimal("4")))), false);
        var otherLot = inventoryService.createLot(lotRequest("4"));
        UUID otherJobId = insert("""
                INSERT INTO production_jobs
                  (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by)
                VALUES (?, ?, ?, ?, 10, ?, ?) RETURNING id
                """, seed.tenantId(), "JOB-" + UUID.randomUUID().toString().substring(0, 8),
                seed.itemId(), seed.siteId(), seed.uomId(), UUID.randomUUID());
        inventoryService.reserveForJob(new ReservationRequest(otherJobId, List.of(
                new ReservationRequirement(seed.itemId(), seed.storeId(), new BigDecimal("4")))), false);
        var availableLot = inventoryService.createLot(lotRequest("3"));

        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/reservations/lots")
                        .param("jobId", jobId.toString())
                        .with(user("keeper").roles("STORE_KEEPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(jobId.toString()))
                .andExpect(jsonPath("$[0].lotId").value(currentLot.id().toString()))
                .andExpect(jsonPath("$[0].itemId").value(seed.itemId().toString()))
                .andExpect(jsonPath("$[0].reservedQty").value(4.0));
        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/reservations/lots")
                        .param("jobId", otherJobId.toString())
                        .with(user("keeper").roles("STORE_KEEPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lotId").value(otherLot.id().toString()));

        asKeeper();
        inventoryService.issueMaterial(new IssueMaterialRequest(
                jobId, currentLot.id(), new BigDecimal("4"), "issue reserved lot"));
        assertThat(inventoryService.getLot(currentLot.id()).qty()).isEqualByComparingTo("0.000");
        assertThatThrownBy(() -> inventoryService.issueMaterial(new IssueMaterialRequest(
                jobId, otherLot.id(), BigDecimal.ONE, "other job lot")))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessageContaining("active reservation");

        asKeeper();
        inventoryService.reserveForJob(new ReservationRequest(jobId, List.of(
                new ReservationRequirement(seed.itemId(), seed.storeId(), new BigDecimal("3")))), false);
        inventoryService.issueMaterial(new IssueMaterialRequest(
                jobId, availableLot.id(), new BigDecimal("3"), "available lot"));
        assertThat(inventoryService.getLot(availableLot.id()).qty()).isEqualByComparingTo("0.000");
    }

    @Test
    void reorderPolicyCreatesOnePendingRequestWhenAvailableStockFallsBelowThreshold() {
        asKeeper();
        inventoryService.createLot(lotRequest("10"));
        replenishmentService.upsertPolicy(new ReorderPolicyRequest(
                seed.itemId(), seed.storeId(), new BigDecimal("9"), new BigDecimal("20"),
                new BigDecimal("30"), 5, null, true));
        double before = meterRegistry.find("boss.inventory.replenishment_requests")
                .tag("outcome", "created").counter() == null
                ? 0
                : meterRegistry.find("boss.inventory.replenishment_requests")
                        .tag("outcome", "created").counter().count();

        inventoryService.reserveForJob(reservationRequest("2"), false);
        assertThat(meterRegistry.counter("boss.inventory.replenishment_requests",
                "outcome", "created").count()).isEqualTo(before + 1);

        var requests = replenishmentService.listRequests(
                "pending", seed.storeId(), seed.itemId(), 0, 20);
        assertThat(requests.content()).singleElement().satisfies(request -> {
            assertThat(request.status()).isEqualTo("pending");
            assertThat(request.requestedQty()).isEqualByComparingTo("20.000");
        });
        assertThat(inventoryService.getPosition(null, seed.itemId(), seed.siteId(), true, 0, 20)
                .content()).hasSize(1);

        var cancelled = replenishmentService.cancelRequest(requests.content().get(0).id());
        assertThat(cancelled.status()).isEqualTo("cancelled");
    }

    @Test
    void customerPoReservationsAreLineLinkedAtomicAndReleasedWhenCancelled() throws Exception {
        asKeeper();
        UUID customerId = insert("""
                INSERT INTO customers (tenant_id, code, name)
                VALUES (?, ?, 'Inventory Test Customer') RETURNING id
                """, seed.tenantId(), "CUST-" + UUID.randomUUID().toString().substring(0, 8));
        UUID cpoId = insert("""
                INSERT INTO cpos
                  (tenant_id, cpo_number, customer_id, site_id, status, order_date, created_by)
                VALUES (?, ?, ?, ?, 'open', CURRENT_DATE, ?) RETURNING id
                """, seed.tenantId(), "CPO-" + UUID.randomUUID().toString().substring(0, 8),
                customerId, seed.siteId(), UUID.randomUUID());
        UUID cpoLineId = insert("""
                INSERT INTO cpo_lines
                  (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price)
                VALUES (?, ?, 1, ?, 5, ?, 1) RETURNING id
                """, seed.tenantId(), cpoId, seed.itemId(), seed.uomId());
        var lot = inventoryService.createLot(lotRequest("3"));
        var request = new CustomerPoReservationRequest(cpoId, List.of(
                new CustomerPoReservationRequirement(cpoLineId, seed.storeId(), new BigDecimal("5"))));
        insert("""
                INSERT INTO tenant_entitlements (tenant_id, feature, enabled)
                VALUES (?, ?, false) RETURNING id
                """, seed.tenantId(), "inventory.customer_po.reservations");
        assertThatThrownBy(() -> inventoryService.reserveForCustomerPo(request, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Inventory capability is disabled");
        jdbc.update("""
                UPDATE tenant_entitlements SET enabled = true
                 WHERE tenant_id = ? AND feature = 'inventory.customer_po.reservations'
                """, seed.tenantId());

        var failed = inventoryService.reserveForCustomerPo(request, false);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(inventoryService.getLot(lot.id()).reservedQty()).isEqualByComparingTo("0.000");

        var partial = inventoryService.reserveForCustomerPo(request, true);
        assertThat(partial.status()).isEqualTo("PARTIALLY_RESERVED");
        assertThat(partial.lines()).singleElement().satisfies(line -> {
            assertThat(line.cpoLineId()).isEqualTo(cpoLineId);
            assertThat(line.reserved()).isEqualByComparingTo("3.000");
            assertThat(line.shortfall()).isEqualByComparingTo("2.000");
        });
        assertThat(jdbc.queryForObject("""
                SELECT cpo_line_id FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_type = 'cpo' AND reference_id = ?
                   AND status = 'active'
                """, UUID.class, seed.tenantId(), cpoId)).isEqualTo(cpoLineId);

        setTenantContext(seed.tenantId(), "OWNER");
        mockMvc.perform(patch("/api/v1/cpos/{cpoId}/status", cpoId)
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"cancelled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        asKeeper();
        assertThat(inventoryService.getLot(lot.id()).reservedQty()).isEqualByComparingTo("0.000");
    }

    @Test
    void interSiteTransferMovesOnlyUnreservedQuantityAndReceivesIntoDestinationStock() throws Exception {
        asKeeper();
        UUID legalEntityId = jdbc.queryForObject("""
                SELECT legal_entity_id FROM physical_sites WHERE tenant_id = ? AND id = ?
                """, UUID.class, seed.tenantId(), seed.siteId());
        UUID destinationSiteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Destination Site', ?) RETURNING id
                """, seed.tenantId(), legalEntityId,
                "DST-" + UUID.randomUUID().toString().substring(0, 8));
        UUID destinationStoreId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Destination Store', ?, 'raw_material') RETURNING id
                """, seed.tenantId(), destinationSiteId,
                "DS-" + UUID.randomUUID().toString().substring(0, 8));

        var sourceLot = inventoryService.createLot(lotRequest("10"));
        inventoryService.reserveForJob(reservationRequest("2"), false);

        assertThatThrownBy(() -> transferService.create(new CreateTransferRequest(
                seed.storeId(), destinationStoreId, null, null,
                List.of(new TransferLineRequest(sourceLot.id(), new BigDecimal("9"))))))
                .isInstanceOf(InventoryValidationException.class)
                .hasMessageContaining("unreserved stock");

        var transfer = transferService.create(new CreateTransferRequest(
                seed.storeId(), destinationStoreId, null, "Move to destination",
                List.of(new TransferLineRequest(sourceLot.id(), new BigDecimal("4")))));
        assertThat(transfer.status()).isEqualTo("in_transit");
        assertThat(transfer.lines()).singleElement().satisfies(line -> {
            assertThat(line.lotId()).isEqualTo(sourceLot.id());
            assertThat(line.qty()).isEqualByComparingTo("4.000");
        });
        mockMvc.perform(get("/api/v1/inter-site-transfers")
                        .with(user("keeper").roles("STORE_KEEPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transferNumber").value(transfer.transferNumber()));
        asKeeper();
        assertThat(inventoryService.getLot(sourceLot.id()).qty()).isEqualByComparingTo("6.000");
        assertThat(inventoryService.getLot(sourceLot.id()).reservedQty()).isEqualByComparingTo("2.000");

        var destinationPosition = inventoryService.getPosition(
                        null, seed.itemId(), null, false, 0, 50).content().stream()
                .filter(row -> row.storeId().equals(destinationStoreId))
                .findFirst().orElseThrow();
        assertThat(destinationPosition.inTransitQty()).isEqualByComparingTo("4.000");

        insert("""
                INSERT INTO tenant_entitlements (tenant_id, feature, enabled)
                VALUES (?, 'inventory.transfers', false) RETURNING id
                """, seed.tenantId());
        assertThatThrownBy(() -> transferService.create(new CreateTransferRequest(
                seed.storeId(), destinationStoreId, null, null,
                List.of(new TransferLineRequest(sourceLot.id(), BigDecimal.ONE)))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Inventory capability is disabled");
        var received = transferService.receive(transfer.id(),
                new ReceiveTransferRequest("Received in good condition"));
        assertThat(received.status()).isEqualTo("received");
        assertThat(inventoryService.listLots(seed.itemId(), destinationStoreId,
                null, 0, 50).content()).extracting(MaterialLotResponse::qty)
                .containsExactly(new BigDecimal("4.000"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_movements
                 WHERE tenant_id = ? AND reference_type = 'TRANSFER'
                   AND reference_id = ? AND movement_type IN ('transfer_out', 'transfer_in')
                """, Integer.class, seed.tenantId(), transfer.lines().getFirst().id())).isEqualTo(2);
        assertThat(inventoryService.getPosition(null, seed.itemId(), null, false, 0, 50).content())
                .filteredOn(row -> row.storeId().equals(destinationStoreId))
                .singleElement().satisfies(row ->
                        assertThat(row.inTransitQty()).isEqualByComparingTo("0.000"));
        assertThatThrownBy(() -> transferService.receive(transfer.id(),
                new ReceiveTransferRequest(null)))
                .hasMessageContaining("Only in-transit transfers can be received");
    }

    @Test
    void interSiteTransferReceivingIntoRestrictedStoresKeepsLotsUnavailable() {
        record RestrictedStore(UUID storeId, String lotStatus) {}

        asKeeper();
        UUID legalEntityId = jdbc.queryForObject("""
                SELECT legal_entity_id FROM physical_sites WHERE tenant_id = ? AND id = ?
                """, UUID.class, seed.tenantId(), seed.siteId());
        UUID destinationSiteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Restricted Destination', ?) RETURNING id
                """, seed.tenantId(), legalEntityId,
                "RDS-" + UUID.randomUUID().toString().substring(0, 8));
        UUID quarantineStoreId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Quarantine Destination', ?, 'quarantine') RETURNING id
                """, seed.tenantId(), destinationSiteId,
                "QDS-" + UUID.randomUUID().toString().substring(0, 8));
        UUID rejectedStoreId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Rejected Destination', ?, 'rejected') RETURNING id
                """, seed.tenantId(), destinationSiteId,
                "RJS-" + UUID.randomUUID().toString().substring(0, 8));
        var sourceLot = inventoryService.createLot(lotRequest("10"));

        for (var destination : List.of(
                new RestrictedStore(quarantineStoreId, "quarantine"),
                new RestrictedStore(rejectedStoreId, "rejected"))) {
            var transfer = transferService.create(new CreateTransferRequest(
                    seed.storeId(), destination.storeId(), null, null,
                    List.of(new TransferLineRequest(sourceLot.id(), new BigDecimal("2")))));
            transferService.receive(transfer.id(), new ReceiveTransferRequest(null));

            var receivedLot = inventoryService.listLots(
                    seed.itemId(), destination.storeId(), null, 0, 10).content().getFirst();
            assertThat(receivedLot.status()).isEqualTo(destination.lotStatus());
            assertThat(receivedLot.availableQty()).isEqualByComparingTo("0.000");
        }

        assertThat(inventoryService.getPosition(null, seed.itemId(), null, false, 0, 50).content())
                .filteredOn(position -> position.storeId().equals(quarantineStoreId)
                        || position.storeId().equals(rejectedStoreId))
                .isEmpty();
    }

    @Test
    void concurrentReservationsNeverExceedTheLockedLotBalance() throws Exception {
        asKeeper();
        inventoryService.createLot(lotRequest("6"));
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(insert("""
                    INSERT INTO production_jobs
                      (tenant_id, job_number, finished_item_id, site_id, quantity, uom_id, created_by)
                    VALUES (?, ?, ?, ?, 1, ?, ?) RETURNING id
                    """, seed.tenantId(), "JOB-" + UUID.randomUUID().toString().substring(0, 8),
                    seed.itemId(), seed.siteId(), seed.uomId(), UUID.randomUUID()));
        }

        var executor = Executors.newFixedThreadPool(10);
        var start = new CountDownLatch(1);
        try {
            var attempts = jobs.stream().map(job -> executor.submit(() -> {
                BossContextHolder.set(new BossContext(seed.tenantId(), UUID.randomUUID(),
                        Set.of("STORE_KEEPER"), null, null));
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Reservation concurrency test did not start");
                    }
                    return inventoryService.reserveForJob(
                            new ReservationRequest(job, List.of(new ReservationRequirement(
                                    seed.itemId(), seed.storeId(), BigDecimal.ONE))), false);
                } finally {
                    BossContextHolder.clear();
                }
            })).toList();
            start.countDown();
            long successful = 0;
            for (var attempt : attempts) {
                if ("FULLY_RESERVED".equals(attempt.get(30, TimeUnit.SECONDS).status())) {
                    successful++;
                }
            }
            assertThat(successful).isEqualTo(6);
            assertThat(jdbc.queryForObject("""
                    SELECT SUM(reserved_qty) FROM material_lots WHERE tenant_id = ?
                    """, BigDecimal.class, seed.tenantId())).isEqualByComparingTo("6.000");
        } finally {
            executor.shutdownNow();
        }
    }

    private CreateLotRequest lotRequest(String quantity) {
        return new CreateLotRequest(seed.itemId(), seed.storeId(), new BigDecimal(quantity),
                BigDecimal.ONE, null, null, null, null);
    }

    private ReservationRequest reservationRequest(String quantity) {
        return new ReservationRequest(jobId, List.of(new ReservationRequirement(
                seed.itemId(), seed.storeId(), new BigDecimal(quantity))));
    }

    private void asKeeper() {
        setTenantContext(seed.tenantId(), "STORE_KEEPER");
    }
}
