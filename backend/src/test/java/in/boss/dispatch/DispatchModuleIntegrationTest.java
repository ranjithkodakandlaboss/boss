package in.boss.dispatch;

import in.boss.dispatch.api.DispatchDtos.*;
import in.boss.dispatch.domain.DispatchService;
import in.boss.dispatch.domain.PackingSpecService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.quality.domain.QualityService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DispatchModuleIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired DispatchService dispatchService;
    @Autowired PackingSpecService packingSpecService;
    @Autowired QualityService qualityService;
    @Autowired MockMvc mockMvc;

    private UUID tenantId;
    private UUID userId;
    private UUID customerId;
    private UUID siteId;
    private UUID storeId;
    private UUID itemId;
    private UUID uomId;
    private UUID cpoId;
    private UUID cpoLineId;
    private UUID jobId;
    private UUID dispatchMethodOptionId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("dispatch-test");
        userId = UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), null, null));

        jdbc.update("""
                INSERT INTO app_users (id, tenant_id, firebase_uid, email, display_name)
                VALUES (?, ?, ?, ?, 'Dispatch Owner')
                """, userId, tenantId, "uid-" + userId, "owner-" + userId + "@example.test");

        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Dispatch Corp', 'Dispatch Corp Ltd') RETURNING id
                """, tenantId);

        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code, site_type)
                VALUES (?, ?, 'Dispatch Factory', 'DF-01', 'factory') RETURNING id
                """, tenantId, legalEntityId);

        storeId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Finished Goods Store', 'FGS-01', 'finished_goods') RETURNING id
                """, tenantId, siteId);

        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Pieces', 'pcs', true) RETURNING id
                """, tenantId);

        itemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, 'ITM-DISP-01', 'Precision Shaft', 'finished_goods', ?) RETURNING id
                """, tenantId, uomId);

        customerId = insert("""
                INSERT INTO customers (tenant_id, code, name, legal_name)
                VALUES (?, 'CUST-01', 'Acme Aerospace', 'Acme Aerospace Ltd') RETURNING id
                """, tenantId);

        cpoId = insert("""
                INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, order_date, status, created_by)
                VALUES (?, 'PO-2026-99', ?, ?, CURRENT_DATE, 'open', ?) RETURNING id
                """, tenantId, customerId, siteId, userId);

        cpoLineId = insert("""
                INSERT INTO cpo_lines (tenant_id, cpo_id, line_no, item_id, quantity, uom_id, unit_price, delivered_qty)
                VALUES (?, ?, 1, ?, 100, ?, 500, 0) RETURNING id
                """, tenantId, cpoId, itemId, uomId);

        jobId = insert("""
                INSERT INTO production_jobs (tenant_id, job_number, cpo_line_id, finished_item_id, site_id, quantity, uom_id, status, created_by)
                VALUES (?, 'JOB-2026-99', ?, ?, ?, 100, ?, 'completed', ?) RETURNING id
                """, tenantId, cpoLineId, itemId, siteId, uomId, userId);

        dispatchMethodOptionId = createConfigOption(tenantId, "DISPATCH_METHOD", "ROAD", "Road Freight");
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    private UUID createConfigOption(UUID optionTenantId, String categoryCode, String code, String label) {
        var previousContext = BossContextHolder.get();
        BossContextHolder.set(new BossContext(optionTenantId, userId, Set.of("OWNER"), null, null));
        try {
            return insert("""
                    INSERT INTO tenant_config_options (tenant_id, category_id, code, label)
                    SELECT ?, id, ?, ? FROM config_categories WHERE code = ?
                    RETURNING id
                    """, optionTenantId, code, label, categoryCode);
        } finally {
            BossContextHolder.set(previousContext);
        }
    }

    private CreateDispatchRequest dispatchRequest(UUID methodOptionId) {
        return new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                null,
                siteId,
                "Dispatch Factory",
                storeId,
                "Finished Goods Store",
                "Blue Dart Express",
                "TS09AB1234",
                "Ramesh Kumar",
                "LR-78901",
                LocalDate.now(),
                LocalDate.now().plusDays(2),
                "Customer Dock 4",
                "Fragile cargo",
                List.of("JOB-2026-99"),
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("25"), uomId, null, null, null)),
                List.of(new CreateDispatchPackageRequest("PKG-01", "Wooden Crate", "Shaft Batch 1", new BigDecimal("25"), "pcs", new BigDecimal("45.5"), new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("50"), "Handle with care")),
                methodOptionId
        );
    }

    @Test
    void testCreateDispatchAndRetrieve() {
        CreateDispatchRequest req = dispatchRequest(dispatchMethodOptionId);

        DispatchDetail created = dispatchService.createDispatch(req);
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.dispatchNumber()).startsWith("DSP-");
        assertThat(created.carrier()).isEqualTo("Blue Dart Express");
        assertThat(created.dispatchMethodOptionId()).isEqualTo(dispatchMethodOptionId);
        assertThat(created.dispatchMethodLabel()).isEqualTo("Road Freight");
        assertThat(created.vehicleNumber()).isEqualTo("TS09AB1234");
        assertThat(created.status()).isEqualTo("pending_docs");
        assertThat(created.lines()).hasSize(1);
        assertThat(created.lines().get(0).quantity()).isEqualByComparingTo(new BigDecimal("25"));
        assertThat(created.packages()).hasSize(1);
        assertThat(created.packages().get(0).packageCode()).isEqualTo("PKG-01");

        // Verify retrieval
        DispatchDetail fetched = dispatchService.getDispatch(created.id());
        assertThat(fetched.dispatchNumber()).isEqualTo(created.dispatchNumber());
        assertThat(fetched.customerName()).isEqualTo("Acme Aerospace");
        assertThat(fetched.dispatchMethodLabel()).isEqualTo("Road Freight");
        assertThat(dispatchService.listDispatches(null, null, null))
                .extracting(DispatchSummary::dispatchMethodLabel)
                .contains("Road Freight");
    }

    @Test
    void testDispatchMethodRejectsCrossTenantAndWrongCategoryOptions() {
        UUID otherTenantId = provisionTenant("dispatch-method-other");
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), null, null));
        UUID crossTenantOptionId = createConfigOption(otherTenantId, "DISPATCH_METHOD", "ROAD", "Other Tenant Road");
        UUID wrongCategoryOptionId = createConfigOption(tenantId, "CARRIER", "CARRIER", "Carrier Option");

        assertThatThrownBy(() -> dispatchService.createDispatch(dispatchRequest(crossTenantOptionId)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active option for this tenant");

        assertThatThrownBy(() -> dispatchService.createDispatch(dispatchRequest(wrongCategoryOptionId)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active option for this tenant");

        jdbc.update("UPDATE tenant_config_options SET is_active = false WHERE id = ?", dispatchMethodOptionId);
        assertThatThrownBy(() -> dispatchService.createDispatch(dispatchRequest(dispatchMethodOptionId)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active option for this tenant");
    }

    @Test
    void testDispatchMethodLabelSnapshotSurvivesOptionRenameAndDeactivation() {
        DispatchDetail created = dispatchService.createDispatch(dispatchRequest(dispatchMethodOptionId));

        jdbc.update("""
                UPDATE tenant_config_options
                SET label = 'Updated Road Freight', is_active = false
                WHERE id = ? AND tenant_id = ?
                """, dispatchMethodOptionId, tenantId);
        jdbc.update("UPDATE dispatches SET notes = 'Unrelated update' WHERE id = ? AND tenant_id = ?",
                created.id(), tenantId);

        DispatchDetail fetched = dispatchService.getDispatch(created.id());
        assertThat(fetched.dispatchMethodOptionId()).isEqualTo(dispatchMethodOptionId);
        assertThat(fetched.dispatchMethodLabel()).isEqualTo("Road Freight");
        assertThat(dispatchService.listDispatches(null, null, null))
                .extracting(DispatchSummary::dispatchMethodLabel)
                .contains("Road Freight");
    }

    @Test
    void testDispatchWithoutMethodRemainsSupported() {
        DispatchDetail created = dispatchService.createDispatch(dispatchRequest(null));
        assertThat(created.dispatchMethodOptionId()).isNull();
        assertThat(created.dispatchMethodLabel()).isNull();
    }

    @Test
    void testDatabaseRejectsInvalidMethodAndProtectsSnapshot() {
        DispatchDetail created = dispatchService.createDispatch(dispatchRequest(dispatchMethodOptionId));
        UUID wrongCategoryOptionId = createConfigOption(tenantId, "CARRIER", "OTHER", "Other");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE dispatches SET dispatch_method_option_id = ? WHERE id = ? AND tenant_id = ?",
                wrongCategoryOptionId, created.id(), tenantId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE dispatches SET dispatch_method_label_snapshot = 'Changed' WHERE id = ? AND tenant_id = ?",
                created.id(), tenantId);
        assertThat(dispatchService.getDispatch(created.id()).dispatchMethodLabel()).isEqualTo("Road Freight");
    }

    @Test
    void testOverDispatchPrevention() {
        CreateDispatchRequest req = new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                null,
                siteId,
                "Dispatch Factory",
                storeId,
                "Finished Goods Store",
                "Blue Dart",
                "TS09AB1234",
                null,
                null,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("150"), uomId, null, null, null)),
                null,
                null
        );

        assertThatThrownBy(() -> dispatchService.createDispatch(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds eligible outstanding quantity");
    }

    @Test
    void testQualityGateBlocksDispatchWhenInspectionFails() {
        // Record failed inspection
        insert("""
                INSERT INTO quality_inspections (
                  tenant_id, inspection_number, inspection_type, reference_type, reference_id,
                  item_id, quantity_inspected, result, status, inspection_date, inspector_id
                ) VALUES (
                  ?, 'INSP-2026-FAIL', 'final', 'production_job', ?,
                  ?, 10, 'fail', 'completed', CURRENT_DATE, ?
                ) RETURNING id
                """, tenantId, jobId, itemId, userId);

        CreateDispatchRequest req = new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                jobId,
                siteId,
                "Dispatch Factory",
                storeId,
                "Finished Goods Store",
                "Carrier",
                "VEH-01",
                null,
                null,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("10"), uomId, null, null, null)),
                null,
                null
        );

        assertThatThrownBy(() -> dispatchService.createDispatch(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not eligible for dispatch");
    }

    @Test
    void testQualityGateAllowsWhenPassed() {
        // Record passed inspection
        insert("""
                INSERT INTO quality_inspections (
                  tenant_id, inspection_number, inspection_type, reference_type, reference_id,
                  item_id, quantity_inspected, quantity_accepted, result, status, inspection_date, inspector_id
                ) VALUES (
                  ?, 'INSP-2026-PASS', 'final', 'production_job', ?,
                  ?, 10, 10, 'pass', 'completed', CURRENT_DATE, ?
                ) RETURNING id
                """, tenantId, jobId, itemId, userId);

        CreateDispatchRequest req = new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                jobId,
                siteId,
                "Dispatch Factory",
                storeId,
                "Finished Goods Store",
                "Safe Express",
                "MH12XX9999",
                null,
                null,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("10"), uomId, null, null, null)),
                null,
                null
        );

        DispatchDetail created = dispatchService.createDispatch(req);
        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo("pending_docs");
    }

    @Test
    void testStatusLifecycleTransitions() {
        CreateDispatchRequest req = new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                null,
                siteId,
                "Dispatch Factory",
                null,
                null,
                "Carrier",
                "VEH-01",
                null,
                null,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("10"), uomId, null, null, null)),
                null,
                null
        );

        DispatchDetail d = dispatchService.createDispatch(req);
        assertThat(d.status()).isEqualTo("pending_docs");

        // pending_docs -> dispatched
        DispatchDetail dispatched = dispatchService.updateStatus(d.id(), new UpdateStatusRequest("dispatched", "Vehicle departed"));
        assertThat(dispatched.status()).isEqualTo("dispatched");

        // dispatched -> in_transit
        DispatchDetail inTransit = dispatchService.updateStatus(d.id(), new UpdateStatusRequest("in_transit", "In transit on NH44"));
        assertThat(inTransit.status()).isEqualTo("in_transit");

        // in_transit -> delivered
        DispatchDetail delivered = dispatchService.updateStatus(d.id(), new UpdateStatusRequest("delivered", "Unloaded at destination"));
        assertThat(delivered.status()).isEqualTo("delivered");
        assertThat(delivered.actualDeliveryDate()).isNotNull();
    }

    @Test
    void testRecordProofOfDeliveryMandatoryReceivedByAndIdempotency() {
        CreateDispatchRequest req = new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                null,
                siteId,
                "Dispatch Factory",
                null,
                null,
                "Carrier",
                "VEH-01",
                null,
                null,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("5"), uomId, null, null, null)),
                null,
                null
        );
        DispatchDetail d = dispatchService.createDispatch(req);
        dispatchService.updateStatus(d.id(), new UpdateStatusRequest("dispatched", null));

        // Missing receivedBy must fail with 400
        assertThatThrownBy(() -> dispatchService.recordPoD(d.id(), new RecordPodRequest("", LocalDate.now(), "good", "POD-01", "Note", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Received by is mandatory");

        // Valid PoD submission
        DispatchDetail podSaved = dispatchService.recordPoD(d.id(), new RecordPodRequest(
                "Harish Chandra",
                LocalDate.now(),
                "good",
                "POD-REF-5544",
                "All items intact",
                "SIG-999"
        ));

        assertThat(podSaved.status()).isEqualTo("delivered");
        assertThat(podSaved.hasPoD()).isTrue();
        assertThat(podSaved.receivedByName()).isEqualTo("Harish Chandra");
        assertThat(podSaved.deliveryCondition()).isEqualTo("good");
        assertThat(podSaved.podReference()).isEqualTo("POD-REF-5544");

        // Idempotent retry: same call succeeds cleanly without error
        DispatchDetail idempotent = dispatchService.recordPoD(d.id(), new RecordPodRequest(
                "Harish Chandra",
                LocalDate.now(),
                "good",
                "POD-REF-5544",
                "All items intact",
                "SIG-999"
        ));
        assertThat(idempotent.hasPoD()).isTrue();
    }

    @Test
    void testDeleteDispatchPreventsWhenDelivered() {
        CreateDispatchRequest req = new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                null,
                siteId,
                "Dispatch Factory",
                null,
                null,
                "Carrier",
                "VEH-01",
                null,
                null,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("5"), uomId, null, null, null)),
                null,
                null
        );
        DispatchDetail d = dispatchService.createDispatch(req);
        dispatchService.updateStatus(d.id(), new UpdateStatusRequest("dispatched", null));
        dispatchService.updateStatus(d.id(), new UpdateStatusRequest("delivered", null));

        assertThatThrownBy(() -> dispatchService.deleteDispatch(d.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot delete a delivered dispatch");
    }

    @Test
    void testPackingSpecCreateAndDispatchPackingRecord() {
        CreatePackingSpecRequest specReq = new CreatePackingSpecRequest(
                itemId,
                null,
                "Reinforced Wooden Box",
                10,
                new BigDecimal("12.5"),
                "kg",
                new Dimensions(new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("20"), "cm"),
                "Top arrow up",
                "Keep dry"
        );
        PackingSpecDto spec = packingSpecService.createPackingSpec(specReq);
        assertThat(spec).isNotNull();
        assertThat(spec.id()).isNotNull();
        assertThat(spec.packingTypeLabel()).isEqualTo("Reinforced Wooden Box");

        // Create dispatch and record packing
        CreateDispatchRequest dReq = new CreateDispatchRequest(
                customerId,
                "Acme Aerospace",
                cpoId,
                null,
                siteId,
                "Dispatch Factory",
                null,
                null,
                "Carrier",
                "VEH-01",
                null,
                null,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                List.of(new CreateDispatchLineRequest(cpoLineId, jobId, itemId, new BigDecimal("20"), uomId, null, null, null)),
                null,
                null
        );
        DispatchDetail d = dispatchService.createDispatch(dReq);

        DispatchPackingRecordDto packRecord = packingSpecService.recordDispatchPacking(d.id(), new RecordPackingRequest(
                spec.id(),
                null,
                "Reinforced Wooden Box",
                2,
                new BigDecimal("25.0"),
                "Sealed with security tape"
        ));

        assertThat(packRecord).isNotNull();
        assertThat(packRecord.actualPacksCount()).isEqualTo(2);

        DispatchDetail refreshed = dispatchService.getDispatch(d.id());
        assertThat(refreshed.packages()).isNotEmpty();
    }
}
