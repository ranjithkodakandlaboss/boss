package in.boss.sales;

import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.documents.api.DocumentDtos.CreateDocumentRequest;
import in.boss.documents.domain.DocumentVaultService;
import in.boss.documents.domain.DocumentValidationException;
import in.boss.dispatch.api.DispatchDtos.CreateDispatchLineRequest;
import in.boss.dispatch.api.DispatchDtos.CreateDispatchRequest;
import in.boss.dispatch.api.DispatchDtos.UpdateStatusRequest;
import in.boss.dispatch.domain.DispatchService;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrRequest;
import in.boss.procurement.domain.PurchaseRequisitionService;
import in.boss.sales.api.SalesApiDtos.CreateQuotationLineRequest;
import in.boss.sales.api.SalesApiDtos.CreateQuotationRequest;
import in.boss.sales.api.SalesApiDtos.CreateRfqRequest;
import in.boss.sales.api.SalesApiDtos.CostRate;
import in.boss.sales.api.SalesApiDtos.QuotationCostingConfigRequest;
import in.boss.sales.api.SalesApiDtos.CounterOfferRequest;
import in.boss.sales.api.SalesApiDtos.OfferedUnitPrice;
import in.boss.sales.api.SalesApiDtos.ReviseQuotationRequest;
import in.boss.sales.api.SalesApiDtos.RfqLineRequest;
import in.boss.sales.api.SalesApiDtos.RfqResponse;
import in.boss.sales.api.SalesApiDtos.UpdateRfqRequest;
import in.boss.sales.api.SalesApiDtos.AcceptQuotationRequest;
import in.boss.sales.domain.CustomerPoService;
import in.boss.sales.domain.QuotationCostingService;
import in.boss.sales.domain.SalesService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
@TestPropertySource(properties = "boss.documents.storage-type=in-memory")
class SalesApiIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private SalesService salesService;

    @Autowired
    private CustomerPoService customerPoService;

    @Autowired
    private QuotationCostingService quotationCostingService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentVaultService documentVault;

    @Autowired
    private PurchaseRequisitionService purchaseRequisitions;

    @Autowired
    private DispatchService dispatchService;

    private UUID tenantId;
    private UUID customerId;
    private UUID siteId;
    private UUID storeId;
    private UUID uomId;
    private UUID materialItemId;
    private UUID finishedItemId;

    @BeforeEach
    void setUpSalesFixtures() {
        tenantId = provisionTenant("sales-test");
        setTenantContext(tenantId, "OWNER");
        UUID userId = BossContextHolder.get().userId();
        jdbc.update("""
                INSERT INTO app_users (id, tenant_id, firebase_uid, email, display_name)
                VALUES (?, ?, ?, ?, 'Sales Owner')
                """, userId, tenantId, "uid-" + userId, "owner-" + userId + "@example.test");
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Sales Test Entity', 'Sales Test Entity Ltd') RETURNING id
                """, tenantId);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Sales Test Site', ?) RETURNING id
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 8));
        storeId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Raw Material Store', ?, 'raw_material') RETURNING id
                """, tenantId, siteId, "RM-" + UUID.randomUUID().toString().substring(0, 8));
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("OWNER"), siteId, null));
        uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, is_base)
                VALUES (?, 'Each', 'ea', TRUE) RETURNING id
                """, tenantId);
        customerId = insert("""
                INSERT INTO customers (tenant_id, code, name)
                VALUES (?, ?, 'Sales Test Customer') RETURNING id
                """, tenantId, "CUST-" + UUID.randomUUID().toString().substring(0, 8));
        materialItemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, std_cost)
                VALUES (?, ?, 'Raw Material', 'raw_material', ?, 2.50) RETURNING id
                """, tenantId, "MAT-" + UUID.randomUUID().toString().substring(0, 8), uomId);
        jdbc.update("""
                INSERT INTO material_lots (tenant_id, item_id, store_id, quantity, unit_cost, status)
                VALUES (?, ?, ?, 10, 2.50, 'available')
                """, tenantId, materialItemId, storeId);
        finishedItemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id)
                VALUES (?, ?, 'Finished Item', 'finished_goods', ?) RETURNING id
                """, tenantId, "FG-" + UUID.randomUUID().toString().substring(0, 8), uomId);

        UUID bomId = insert("""
                INSERT INTO bom_headers
                  (tenant_id, finished_item_id, version, status, created_by)
                VALUES (?, ?, ?, 'approved', ?) RETURNING id
                """, tenantId, finishedItemId, "v-" + UUID.randomUUID().toString().substring(0, 8), userId);
        jdbc.update("""
                INSERT INTO bom_lines
                  (tenant_id, bom_header_id, component_item_id, quantity, uom_id, level)
                VALUES (?, ?, ?, 2.0, ?, 1)
                """, tenantId, bomId, materialItemId, uomId);
    }

    @AfterEach
    void clearSalesContext() {
        BossContextHolder.clear();
    }

    @Test
    void customerRatingCanBeReadUpdatedClearedAndValidated() throws Exception {
        BossContext context = BossContextHolder.get();
        String body = mockMvc.perform(post("/api/v1/customers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"RATING-TEST\",\"name\":\"Rated Customer\",\"rating\":0}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rating").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        for (String value : List.of("5", "6", "null")) {
            BossContextHolder.set(context);
            var result = mockMvc.perform(patch("/api/v1/customers/{id}", id)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":" + value + "}"));
            if ("6".equals(value)) {
                result.andExpect(status().isBadRequest());
            } else {
                result.andExpect(status().isOk());
                if ("null".equals(value)) result.andExpect(jsonPath("$.rating").isEmpty());
                else result.andExpect(jsonPath("$.rating").value(5));
            }
        }
        BossContextHolder.set(context);
        mockMvc.perform(get("/api/v1/customers/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rating").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "PATCH"})
    void customerDeactivationCancelsPendingSalesAndPreservesCompletedHistory(String method) throws Exception {
        var completedQuote = sentQuotation(new BigDecimal("20.00"));
        var completedPo = customerPoService.accept(completedQuote.id(), new AcceptQuotationRequest(null, null)).cpo();
        jdbc.update("UPDATE cpos SET status = 'fulfilled' WHERE id = ?", completedPo.id());
        var pendingQuote = sentQuotation(new BigDecimal("20.00"));
        var pendingPo = customerPoService.accept(pendingQuote.id(), new AcceptQuotationRequest(null, null)).cpo();
        jdbc.update("UPDATE cpos SET status = 'feasibility_pending' WHERE id = ?", pendingPo.id());
        var rfq = salesService.createRfq(new CreateRfqRequest(customerId, null, null, null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, null))));
        var draft = draftQuotation(rfq.id());
        BossContext context = BossContextHolder.get();
        var request = "DELETE".equals(method) ? delete("/api/v1/customers/{id}", customerId)
                : patch("/api/v1/customers/{id}", customerId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"inactive\"}");
        mockMvc.perform(request.header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().is2xxSuccessful());
        BossContextHolder.set(context);
        assertThat(jdbc.queryForObject("SELECT is_active FROM customers WHERE id = ?", Boolean.class, customerId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM rfqs WHERE id = ?", String.class, rfq.id())).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT status FROM quotations WHERE id = ?", String.class, draft.id())).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT status FROM cpos WHERE id = ?", String.class, pendingPo.id())).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT status FROM quotations WHERE id = ?", String.class, pendingQuote.id())).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT status FROM cpos WHERE id = ?", String.class, completedPo.id())).isEqualTo("fulfilled");
        assertThat(jdbc.queryForObject("SELECT status FROM quotations WHERE id = ?", String.class, completedQuote.id())).isEqualTo("accepted");
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "partial", "feasibility_approved"})
    void activeCustomerPoBlocksDeactivationWithoutClosingDrafts(String poStatus) throws Exception {
        var quote = sentQuotation(new BigDecimal("20.00"));
        var po = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        jdbc.update("UPDATE cpos SET status = ? WHERE id = ?", poStatus, po.id());
        var draft = draftQuotation(null);
        BossContext context = BossContextHolder.get();
        for (String method : List.of("DELETE", "PATCH")) {
            BossContextHolder.set(context);
            var request = "DELETE".equals(method) ? delete("/api/v1/customers/{id}", customerId)
                    : patch("/api/v1/customers/{id}", customerId).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"inactive\",\"name\":\"Must not be saved\"}");
            mockMvc.perform(request.header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andExpect(status().isConflict());
        }
        BossContextHolder.set(context);
        assertThat(jdbc.queryForObject("SELECT is_active FROM customers WHERE id = ?", Boolean.class, customerId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, customerId))
                .isEqualTo("Sales Test Customer");
        assertThat(jdbc.queryForObject("SELECT status FROM quotations WHERE id = ?", String.class, draft.id())).isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT status FROM cpos WHERE id = ?", String.class, po.id())).isEqualTo(poStatus);
    }

    @Test
    void inactiveCustomerRejectsEditsNewOrdersAndDocumentsButAllowsExplicitReactivation() throws Exception {
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE id = ?", customerId);
        BossContext context = BossContextHolder.get();
        SecurityContext authentication = SecurityContextHolder.getContext();
        for (String body : List.of("{\"name\":\"Edited\",\"rating\":5}", "{\"status\":\"active\",\"name\":\"Edited\"}")) {
            BossContextHolder.set(context);
            mockMvc.perform(patch("/api/v1/customers/{id}", customerId)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict());
        }
        BossContextHolder.set(context);
        mockMvc.perform(post("/api/v1/cpos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","siteId":"%s","directOrderReason":"Repeat order",
                                 "lines":[{"itemId":"%s","quantity":1,"uomId":"%s","unitPrice":20}]}
                                """.formatted(customerId, siteId, finishedItemId, uomId)))
                .andExpect(status().isConflict());
        BossContextHolder.set(context);
        SecurityContextHolder.setContext(authentication);
        var document = new CreateDocumentRequest("Drawing", "Job Documents", null, null, null,
                null, null, "Customer", customerId, "Sales Test Customer");
        assertThatThrownBy(() -> documentVault.uploadDocument(document,
                new MockMultipartFile("file", "drawing.pdf", "application/pdf", new byte[] {1})))
                .isInstanceOf(DocumentValidationException.class).hasMessageContaining("inactive");
        mockMvc.perform(patch("/api/v1/customers/{id}", customerId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"active\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.name").value("Sales Test Customer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"job", "delivery", "invoice"})
    void onHoldCustomerPoWithExecutionProgressStillBlocksDeactivation(String progress) throws Exception {
        var quote = sentQuotation(new BigDecimal("20.00"));
        var po = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        jdbc.update("UPDATE cpos SET status = 'on_hold' WHERE id = ?", po.id());
        UUID lineId = po.lines().getFirst().id();
        if ("job".equals(progress)) {
            jdbc.update("""
                    INSERT INTO production_jobs
                      (tenant_id, job_number, cpo_line_id, finished_item_id, site_id, quantity, uom_id, created_by)
                    VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                    """, tenantId, "JOB-" + UUID.randomUUID(), lineId, finishedItemId, siteId, uomId,
                    BossContextHolder.get().userId());
        } else if ("delivery".equals(progress)) {
            jdbc.update("UPDATE cpo_lines SET delivered_qty = 1 WHERE id = ?", lineId);
        } else {
            jdbc.update("UPDATE cpo_lines SET invoiced_qty = 1 WHERE id = ?", lineId);
        }
        mockMvc.perform(delete("/api/v1/customers/{id}", customerId))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT is_active FROM customers WHERE id = ?", Boolean.class, customerId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM cpos WHERE id = ?", String.class, po.id())).isEqualTo("on_hold");
    }

    @Test
    void inactiveCustomerSalesRecordsRejectMutationsEvenIfTheyPredateDeactivationRules() {
        var quote = sentQuotation(new BigDecimal("20.00"));
        var po = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        var draft = draftQuotation(null);
        var rfq = salesService.createRfq(new CreateRfqRequest(customerId, null, null, null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, null))));
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE id = ?", customerId);
        assertThatThrownBy(() -> salesService.updateRfqStatus(rfq.id(), "sent"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Inactive");
        assertThatThrownBy(() -> salesService.updateQuotationStatus(draft.id(), "sent"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Inactive");
        assertThatThrownBy(() -> customerPoService.updateStatus(po.id(), "on_hold"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Inactive");
        assertThatThrownBy(() -> customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Inactive");
    }

    @Test
    void cancelledRfqRejectsLinkedDocumentUpload() {
        var rfq = salesService.createRfq(new CreateRfqRequest(customerId, null, null, null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, null))));
        salesService.updateRfqStatus(rfq.id(), "cancelled");
        var body = new CreateDocumentRequest("Drawing", "Job Documents", null, null, null,
                null, null, "RFQ", rfq.id(), rfq.rfqNumber());
        var file = new MockMultipartFile("file", "drawing.pdf", "application/pdf", new byte[] {1});
        assertThatThrownBy(() -> documentVault.uploadDocument(body, file))
                .isInstanceOf(DocumentValidationException.class).hasMessageContaining("cancelled");
    }

    @Test
    void cancelledOrderCannotCreatePurchaseRequisition() {
        var quote = sentQuotation(new BigDecimal("20.00"));
        var order = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        customerPoService.updateStatus(order.id(), "cancelled");
        var line = new CreatePrLineRequest(materialItemId, BigDecimal.ONE, null, uomId, null,
                storeId, null, null, null);
        var request = new CreatePrRequest("customer_po", null, null, siteId, null, null, null,
                null, null, List.of(line), order.id());
        assertThatThrownBy(() -> purchaseRequisitions.createPr(request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cancelled");
    }

    @Test
    void cancelledOrderCannotStartDispatchEvenWhenOnlyItsLineIsLinked() {
        var quote = sentQuotation(new BigDecimal("20.00"));
        var order = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        customerPoService.updateStatus(order.id(), "cancelled");
        var line = new CreateDispatchLineRequest(order.lines().getFirst().id(), null, finishedItemId,
                BigDecimal.ONE, uomId, null, null, null);
        var request = new CreateDispatchRequest(customerId, null, null, null, siteId, null,
                null, null, null, null, null, null, LocalDate.now(), null, null,
                null, null, List.of(line), null, null);
        assertThatThrownBy(() -> dispatchService.createDispatch(request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cancelled");
    }

    @Test
    void dispatchDraftCannotChangeDeliveredQuantityOnCancelledOrder() {
        var quote = sentQuotation(new BigDecimal("20.00"));
        var order = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        UUID cpoLineId = order.lines().getFirst().id();
        UUID fgStoreId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'FG Store', ?, 'finished_goods') RETURNING id
                """, tenantId, siteId, "FG-STORE-" + UUID.randomUUID().toString().substring(0, 8));
        UUID lotId = insert("""
                INSERT INTO material_lots (tenant_id, item_id, store_id, lot_number, quantity, reserved_qty, status)
                VALUES (?, ?, ?, 'LOT-FG-01', 10, 0, 'available') RETURNING id
                """, tenantId, finishedItemId, fgStoreId);
        UUID jobId = insert("""
                INSERT INTO production_jobs (tenant_id, job_number, cpo_id, cpo_line_id, finished_item_id, site_id, quantity, uom_id, status, created_by)
                VALUES (?, 'JOB-2026-SALES', ?, ?, ?, ?, 10, ?, 'completed', ?) RETURNING id
                """, tenantId, order.id(), cpoLineId, finishedItemId, siteId, uomId, BossContextHolder.get().userId());
        jdbc.update("""
                INSERT INTO quality_inspections (
                  tenant_id, inspection_number, inspection_type, reference_type, reference_id,
                  item_id, quantity_inspected, quantity_accepted, result, status, inspection_date, inspector_id
                ) VALUES (?, 'INSP-2026-PASS', 'final', 'production_job', ?, ?, 10, 10,
                          'pass', 'completed', CURRENT_DATE, ?)
                """, tenantId, jobId, finishedItemId, BossContextHolder.get().userId());
        jdbc.update("""
                INSERT INTO clearance_certificates
                  (tenant_id, cert_number, job_id, customer_id, status, issued_at, issued_by)
                VALUES (?, ?, ?, ?, 'issued', NOW(), ?)
                """, tenantId, "CERT-" + UUID.randomUUID(), jobId, customerId, BossContextHolder.get().userId());
        var line = new CreateDispatchLineRequest(cpoLineId, jobId, finishedItemId,
                BigDecimal.ONE, uomId, lotId, null, null);
        var request = new CreateDispatchRequest(customerId, null, order.id(), jobId, siteId, null,
                fgStoreId, "FG Store", null, null, null, null, LocalDate.now(), null, null,
                null, null, List.of(line), null, null);
        var dispatch = dispatchService.createDispatch(request);
        customerPoService.updateStatus(order.id(), "cancelled");
        assertThatThrownBy(() -> dispatchService.updateStatus(dispatch.id(), new UpdateStatusRequest("dispatched", null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cancelled");
    }

    @Test
    void draftDetailAndListRefreshMissingBomEstimateWithoutChangingHistoricalCost() throws Exception {
        BossContext context = BossContextHolder.get();
        jdbc.update("UPDATE bom_headers SET status = 'draft' WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE material_lots SET unit_cost = NULL WHERE tenant_id = ?", tenantId);
        var draft = draftQuotation(null);
        assertThat(draft.costing().unavailableReasons()).containsExactly("Line 1 has no approved BOM");

        jdbc.update("UPDATE bom_headers SET status = 'approved' WHERE tenant_id = ?", tenantId);
        var detail = mockMvc.perform(get("/api/v1/quotations/{id}", draft.id()))
                .andExpect(status().isOk()).andReturn();
        BossContextHolder.set(context);
        jdbc.update("UPDATE material_lots SET unit_cost = 2.50 WHERE tenant_id = ?", tenantId);
        var list = mockMvc.perform(get("/api/v1/quotations").param("status", "draft"))
                .andExpect(status().isOk()).andReturn();
        var detailCost = objectMapper.readTree(detail.getResponse().getContentAsString()).path("costing");
        var listCost = objectMapper.readTree(list.getResponse().getContentAsString()).path("content").get(0).path("costing");
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(detailCost.path("unavailableReasons").get(0).asText()).contains("inventory");
            soft.assertThat(listCost.path("available").asBoolean()).isTrue();
            soft.assertThat(listCost.path("materialCost").decimalValue()).isEqualByComparingTo("5.00");
        });

        BossContextHolder.set(context);
        jdbc.update("UPDATE material_lots SET unit_cost = 4.00 WHERE tenant_id = ?", tenantId);
        mockMvc.perform(patch("/api/v1/quotations/{id}/status", draft.id())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"sent\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.costing.materialCost").value(8));
        BossContextHolder.set(context);
        jdbc.update("UPDATE material_lots SET unit_cost = 9.00 WHERE tenant_id = ?", tenantId);
        mockMvc.perform(get("/api/v1/quotations/{id}", draft.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.costing.materialCost").value(8));
    }

    @Test
    void submitRechecksIncompleteDraftCostAfterInventoryIsValued() throws Exception {
        BossContext context = BossContextHolder.get();
        // Missing inventory valuation: costing unavailable — submission is now warn-only (not blocked)
        jdbc.update("UPDATE material_lots SET unit_cost = NULL WHERE tenant_id = ?", tenantId);
        var draftNoCosting = draftQuotation(null);
        assertThat(draftNoCosting.costing().available()).isFalse();
        mockMvc.perform(patch("/api/v1/quotations/{id}/status", draftNoCosting.id())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"sent\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("sent"));
        BossContextHolder.set(context);
        // With valued inventory, submission on a fresh draft recalculates and persists costing
        jdbc.update("UPDATE material_lots SET unit_cost = 2.50 WHERE tenant_id = ?", tenantId);
        BossContextHolder.set(context);
        var draftWithCosting = draftQuotation(null);
        mockMvc.perform(patch("/api/v1/quotations/{id}/status", draftWithCosting.id())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"sent\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("sent"))
                .andExpect(jsonPath("$.costing.available").value(true));
        BossContextHolder.set(context);
        mockMvc.perform(get("/api/v1/quotations/{id}", draftWithCosting.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("sent"));
    }

    @Test
    void approvedBomWithUnvaluedStockDoesNotReportMissingBom() {
        jdbc.update("UPDATE material_lots SET unit_cost = NULL WHERE tenant_id = ?", tenantId);
        var draft = draftQuotation(null);
        assertThat(draft.costing().lines().getFirst().bomSnapshot().getBomHeaderId()).isNotNull();
        assertThat(draft.costing().unavailableReasons()).singleElement().asString()
                .contains("inventory").doesNotContain("no approved BOM");
    }

    @Test
    void cancellingRfqAlsoCancelsAcceptedQuoteAndOrderAndBlocksNewActions() {
        var quote = sentQuotation(new BigDecimal("20.00"));
        var order = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        assertThat(salesService.updateRfqStatus(quote.rfqId(), "cancelled").status()).isEqualTo("cancelled");
        assertThat(salesService.getQuotation(quote.id()).status()).isEqualTo("cancelled");
        assertThat(customerPoService.get(order.id()).status()).isEqualTo("cancelled");
        assertThatThrownBy(() -> customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cancelled");
        assertThatThrownBy(() -> customerPoService.checkFeasibility(order.id()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> draftQuotation(quote.rfqId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cancelled");
    }

    @Test
    void cancellingQuoteAlsoCancelsLinkedOrder() throws Exception {
        BossContext context = BossContextHolder.get();
        var quote = sentQuotation(new BigDecimal("20.00"));
        var order = customerPoService.accept(quote.id(), new AcceptQuotationRequest(null, null)).cpo();
        mockMvc.perform(patch("/api/v1/quotations/{id}/status", quote.id())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"cancelled\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        BossContextHolder.set(context);
        mockMvc.perform(get("/api/v1/cpos/{id}", order.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void directOrderReturnsAnIdReadableWithoutAQuotationAndRejectsRawMaterials() throws Exception {
        BossContext context = BossContextHolder.get();
        String request = """
                {"customerId":"%s","siteId":"%s","directOrderReason":"Direct test","paymentTerms":30,
                 "lines":[{"itemId":"%s","uomId":"%s","description":"Test item","quantity":1,"unitPrice":20}]}
                """.formatted(customerId, siteId, finishedItemId, uomId);
        String body = mockMvc.perform(post("/api/v1/cpos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        BossContextHolder.set(context);
        mockMvc.perform(get("/api/v1/cpos/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.quotationId").isEmpty());
        BossContextHolder.set(context);
        mockMvc.perform(post("/api/v1/cpos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace(finishedItemId.toString(), materialItemId.toString())))
                .andExpect(status().isBadRequest());
    }

    private in.boss.sales.api.SalesApiDtos.QuotationResponse draftQuotation(UUID rfqId) {
        return salesService.createQuotation(new CreateQuotationRequest(customerId, rfqId, null,
                List.of(new CreateQuotationLineRequest(finishedItemId, BigDecimal.ONE, uomId,
                        new BigDecimal("20.00"), 1, null, null, "DRW-14", null, null, null))));
    }

    @Test
    void rfqLinesAreEditableOnlyInDraftAndStatusAdvances() {
        RfqResponse created = salesService.createRfq(new CreateRfqRequest(
                customerId,
                null,
                null,
                null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, "initial"))));
        assertThat(created.status()).isEqualTo("draft");
        assertThat(created.lines()).hasSize(1);
        UUID oldLineId = created.lines().get(0).id();

        RfqResponse updated = salesService.updateDraftRfq(created.id(), new UpdateRfqRequest(
                null,
                null,
                null,
                List.of(new RfqLineRequest(finishedItemId, new BigDecimal("3"), uomId, "updated"))));
        assertThat(updated.lines()).hasSize(1);
        assertThat(updated.lines().get(0).qty()).isEqualByComparingTo("3");
        assertThat(updated.lines().get(0).id()).isNotEqualTo(oldLineId);
        assertThat(salesService.listRfqs(customerId, "draft", 0, 20).content())
                .extracting(RfqResponse::id)
                .contains(created.id());

        assertThat(salesService.updateRfqStatus(created.id(), "sent").status()).isEqualTo("sent");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE tenant_id = ? AND entity_type = 'RFQ' AND entity_id = ?
                  AND action = 'STATUS_SENT' AND new_data ->> 'status' = 'sent'
                """, Integer.class, tenantId, created.id())).isEqualTo(1);
        assertThatThrownBy(() -> salesService.updateDraftRfq(created.id(), new UpdateRfqRequest(
                null, null, null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, null)))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(salesService.updateRfqStatus(created.id(), "closed").status()).isEqualTo("closed");
    }

    @Test
    void quotationRevisionPreservesOriginalAndCalculatesInternalBomEstimate() {
        RfqResponse rfq = salesService.createRfq(new CreateRfqRequest(
                customerId,
                null,
                null,
                null,
                List.of(new RfqLineRequest(finishedItemId, new BigDecimal("3"), uomId, null))));
        var created = salesService.createQuotation(new CreateQuotationRequest(
                customerId,
                rfq.id(),
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, new BigDecimal("3"), uomId, new BigDecimal("20.00"), 2))));
        assertThat(created.rfqId()).isEqualTo(rfq.id());

        assertThat(jdbc.queryForObject(
                "SELECT estimated_material_cost FROM quotations WHERE tenant_id = ? AND id = ?",
                BigDecimal.class, tenantId, created.id())).isEqualByComparingTo("15.00");
        assertThat(jdbc.queryForObject(
                "SELECT estimated_material_cost_per_unit FROM quotation_lines WHERE tenant_id = ? AND quotation_id = ?",
                BigDecimal.class, tenantId, created.id())).isEqualByComparingTo("5.00");

        var revised = salesService.reviseQuotation(created.id(), new ReviseQuotationRequest(
                "Customer requested revised pricing",
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, new BigDecimal("3"), uomId, new BigDecimal("22.00"), 2))));

        assertThat(revised.id()).isNotEqualTo(created.id());
        assertThat(revised.quotationNumber()).isEqualTo(created.quotationNumber());
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.status()).isEqualTo("draft");
        assertThat(salesService.getQuotation(created.id()).status()).isEqualTo("superseded");
        assertThat(jdbc.queryForObject(
                "SELECT unit_price FROM quotation_lines WHERE tenant_id = ? AND quotation_id = ?",
                BigDecimal.class, tenantId, created.id())).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM quotation_revisions WHERE tenant_id = ? AND quotation_id = ?",
                Integer.class, tenantId, created.id())).isEqualTo(1);
    }

    @Test
    void specificationDrivenBomIsSnapshottedOnQuotationAndDoesNotChangeAfterBomRevision() {
        jdbc.update("""
                UPDATE bom_lines
                   SET quantity_formula = 'length * width'
                 WHERE tenant_id = ? AND bom_header_id = (
                   SELECT id FROM bom_headers WHERE tenant_id = ? AND finished_item_id = ? AND status = 'approved'
                 )
                """, tenantId, tenantId, finishedItemId);
        var rfq = salesService.createRfq(new CreateRfqRequest(
                customerId, null, null, null,
                List.of(new RfqLineRequest(finishedItemId, new BigDecimal("3"), uomId, null,
                        "DRW-SPEC", "A", null, "Countertop", null, null, null,
                        java.util.Map.of("length", new BigDecimal("2"), "width", new BigDecimal("3"))))));

        assertThat(rfq.lines().get(0).specificationValues())
                .containsEntry("length", new BigDecimal("2"))
                .containsEntry("width", new BigDecimal("3"));
        var rfqLine = rfq.lines().get(0);
        var quotation = salesService.createQuotation(new CreateQuotationRequest(
                customerId, rfq.id(), null,
                List.of(new CreateQuotationLineRequest(finishedItemId, new BigDecimal("3"), uomId,
                        new BigDecimal("20"), 2, null, null, "DRW-SPEC", "A", "Countertop", null,
                        java.util.Map.of(), java.util.Map.of(), rfqLine.id(), rfqLine.specificationValues()))));

        var snapshot = quotation.costing().lines().get(0).bomSnapshot();
        assertThat(quotation.costing().materialCost()).isEqualByComparingTo("45.00");
        assertThat(snapshot.getBomHeaderId()).isNotNull();
        assertThat(snapshot.getBomVersion()).isNotBlank();
        assertThat(snapshot.getSpecifications()).containsEntry("length", new BigDecimal("2"));
        assertThat(snapshot.getFlatMaterials()).singleElement()
                .extracting(material -> material.getQty()).isEqualTo(new BigDecimal("6.0000"));

        jdbc.update("""
                UPDATE bom_lines
                   SET quantity_formula = 'length * width * 2'
                 WHERE tenant_id = ? AND bom_header_id = (
                   SELECT id FROM bom_headers WHERE tenant_id = ? AND finished_item_id = ? AND status = 'approved'
                 )
                """, tenantId, tenantId, finishedItemId);
        var historical = salesService.getQuotation(quotation.id());
        assertThat(historical.costing().materialCost()).isEqualByComparingTo("45.00");
        assertThat(historical.costing().lines().get(0).bomSnapshot().getFlatMaterials()).singleElement()
                .extracting(material -> material.getQty()).isEqualTo(new BigDecimal("6.0000"));
        assertThat(historical.lines().get(0).specificationValues()).containsEntry("width", new BigDecimal("3"));
    }

    @Test
    void quotationCostingUsesWeightedAverageSnapshotsAndRequiresReasonedApproval() {
        jdbc.update("DELETE FROM material_lots WHERE tenant_id = ? AND item_id = ?", tenantId, materialItemId);
        assertThat(quotationCostingService.materialCostPerUnit(tenantId, finishedItemId, uomId)).isNull();
        jdbc.update("""
                INSERT INTO material_lots (tenant_id, item_id, store_id, quantity, unit_cost, status)
                VALUES (?, ?, ?, 10, 2.50, 'available')
                """, tenantId, materialItemId, storeId);
        jdbc.update("""
                INSERT INTO material_lots (tenant_id, item_id, store_id, quantity, unit_cost, status)
                VALUES (?, ?, ?, 30, 3.50, 'available')
                """, tenantId, materialItemId, storeId);
        jdbc.update("UPDATE material_lots SET unit_cost = NULL WHERE tenant_id = ? AND item_id = ? AND quantity = 10",
                tenantId, materialItemId);
        assertThat(quotationCostingService.materialCostPerUnit(tenantId, finishedItemId, uomId)).isNull();
        jdbc.update("UPDATE material_lots SET unit_cost = 2.50 WHERE tenant_id = ? AND item_id = ? AND quantity = 10",
                tenantId, materialItemId);
        jdbc.update("UPDATE items SET std_cost = 99.00 WHERE tenant_id = ? AND id = ?",
                tenantId, materialItemId);
        assertThatThrownBy(() -> quotationCostingService.saveConfig(new QuotationCostingConfigRequest(
                List.of(new CostRate("Machining", BigDecimal.ONE),
                        new CostRate("machining", BigDecimal.TEN)),
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("20.00"), new BigDecimal("10.00"), new BigDecimal("15.00"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        var savedConfig = quotationCostingService.saveConfig(new QuotationCostingConfigRequest(
                List.of(new CostRate("Machining", new BigDecimal("100.00"))),
                List.of(new CostRate("Turning", new BigDecimal("80.00"))),
                new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("3.00"),
                new BigDecimal("4.00"), new BigDecimal("35.00"), new BigDecimal("20.00"),
                new BigDecimal("25.00")));
        assertThat(savedConfig.labourRates()).hasSize(1);
        assertThat(quotationCostingService.calculate(tenantId,
                List.of(new CreateQuotationLineRequest(finishedItemId, BigDecimal.ONE, uomId, BigDecimal.ZERO, null)),
                BigDecimal.ZERO).requiresApproval()).isTrue();

        var quotation = salesService.createQuotation(new CreateQuotationRequest(
                customerId, null, null,
                List.of(new CreateQuotationLineRequest(finishedItemId, new BigDecimal("3"), uomId,
                        new BigDecimal("20.00"), null, null, null, "DRW-AVG", null, null, null,
                        java.util.Map.of("Machining", new BigDecimal("2.00")),
                        java.util.Map.of("Turning", new BigDecimal("1.50"))))));
        assertThat(quotation.costing().materialCost()).isEqualByComparingTo("19.50");
        assertThat(quotation.costing().labourCost()).isEqualByComparingTo("200.00");
        assertThat(quotation.costing().machineCost()).isEqualByComparingTo("120.00");
        assertThat(quotation.costing().totalCost()).isEqualByComparingTo("369.50");
        assertThat(quotation.costing().grossProfit()).isEqualByComparingTo("-309.50");
        assertThat(quotation.costing().grossMarginPct()).isEqualByComparingTo("-515.8333");
        assertThat(quotation.costing().requiresApproval()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT estimated_material_cost_per_unit FROM quotation_lines
                WHERE tenant_id = ? AND quotation_id = ?
                """, BigDecimal.class, tenantId, quotation.id())).isEqualByComparingTo("6.50");

        BossContextHolder.set(new BossContext(tenantId, BossContextHolder.get().userId(),
                Set.of("SALES_MANAGER"), siteId, null));
        assertThatThrownBy(() -> salesService.updateQuotationStatus(quotation.id(), "sent", "discount approved"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        setTenantContext(tenantId, "OWNER");
        BossContextHolder.set(new BossContext(tenantId, BossContextHolder.get().userId(),
                Set.of("OWNER"), siteId, null));
        assertThatThrownBy(() -> salesService.updateQuotationStatus(quotation.id(), "sent"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        var issued = salesService.updateQuotationStatus(quotation.id(), "sent", "Approved strategic order");
        assertThat(issued.costOverrideReason()).isEqualTo("Approved strategic order");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE tenant_id = ? AND entity_type = 'QUOTATION' AND entity_id = ?
                  AND action = 'STATUS_SENT'
                  AND new_data ->> 'costOverrideReason' = 'Approved strategic order'
                """, Integer.class, tenantId, quotation.id())).isEqualTo(1);

        jdbc.update("UPDATE material_lots SET unit_cost = 500 WHERE tenant_id = ? AND item_id = ?",
                tenantId, materialItemId);
        quotationCostingService.saveConfig(new QuotationCostingConfigRequest(
                List.of(new CostRate("Machining", new BigDecimal("900.00"))),
                List.of(new CostRate("Turning", new BigDecimal("700.00"))),
                new BigDecimal("20.00"), new BigDecimal("10.00"), new BigDecimal("30.00"),
                new BigDecimal("40.00"), new BigDecimal("35.00"), new BigDecimal("20.00"),
                new BigDecimal("25.00")));
        assertThat(salesService.getQuotation(quotation.id()).costing().materialCost()).isEqualByComparingTo("19.50");
        assertThat(salesService.getQuotation(quotation.id()).costing().totalCost()).isEqualByComparingTo("369.50");

        UUID anotherTenant = provisionTenant("sales-costing-other");
        setTenantContext(anotherTenant, "OWNER");
        assertThat(quotationCostingService.getConfig().labourRates()).isEmpty();
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "SALES_MANAGER")
    void salesManagerCannotChangeTenantCostingConfiguration() throws Exception {
        QuotationCostingConfigRequest request = new QuotationCostingConfigRequest(
                List.of(new CostRate("Machining", new BigDecimal("100.00"))), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("20.00"), new BigDecimal("10.00"), new BigDecimal("15.00"));
        mockMvc.perform(put("/api/v1/quotations/costing-config")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void quotationNegotiationAlternatesOffersAndKeepsHistoryAppendOnly() {
        var created = salesService.createQuotation(new CreateQuotationRequest(
                customerId,
                null,
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, new BigDecimal("3"), uomId, new BigDecimal("20.00"), 2,
                        null, null, "DRW-14", null, null, null))));
        UUID lineId = created.lines().get(0).id();

        assertThatThrownBy(() -> salesService.updateQuotationStatus(created.id(), "expired"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(salesService.updateQuotationStatus(created.id(), "sent").status()).isEqualTo("sent");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE tenant_id = ? AND entity_type = 'QUOTATION' AND entity_id = ?
                  AND action = 'STATUS_SENT' AND new_data ->> 'status' = 'sent'
                """, Integer.class, tenantId, created.id())).isEqualTo(1);

        var customerOffer = salesService.recordCounterOffer(created.id(), new CounterOfferRequest(
                List.of(new OfferedUnitPrice(lineId, new BigDecimal("18.00"))), "Customer counter"));
        assertThat(customerOffer.round()).isEqualTo(1);
        assertThat(customerOffer.partyType()).isEqualTo("CUSTOMER");
        assertThat(jdbc.queryForObject(
                "SELECT material_cost_at_time FROM quotation_negotiation_history WHERE tenant_id = ? AND id = ?",
                BigDecimal.class, tenantId, customerOffer.id())).isEqualByComparingTo("15.00");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE tenant_id = ? AND entity_type = 'QUOTATION' AND entity_id = ?
                  AND action = 'STATUS_NEGOTIATING' AND new_data ->> 'status' = 'negotiating'
                """, Integer.class, tenantId, created.id())).isEqualTo(1);
        assertThat(salesService.getQuotation(created.id()).status()).isEqualTo("negotiating");

        assertThatThrownBy(() -> salesService.recordCounterOffer(created.id(), new CounterOfferRequest(
                List.of(new OfferedUnitPrice(lineId, new BigDecimal("17.00"))), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        var tenantOffer = salesService.respondToCounterOffer(created.id(), new CounterOfferRequest(
                List.of(new OfferedUnitPrice(lineId, new BigDecimal("19.00"))), "Tenant response"));
        assertThat(tenantOffer.round()).isEqualTo(1);
        assertThat(tenantOffer.partyType()).isEqualTo("TENANT");

        var nextCustomerOffer = salesService.recordCounterOffer(created.id(), new CounterOfferRequest(
                List.of(new OfferedUnitPrice(lineId, new BigDecimal("18.50"))), null));
        assertThat(nextCustomerOffer.round()).isEqualTo(2);
        var quotation = salesService.getQuotation(created.id());
        assertThat(quotation.negotiationHistory()).hasSize(3);
        assertThat(quotation.negotiationHistory())
                .extracting(entry -> entry.partyType())
                .containsExactly("CUSTOMER", "TENANT", "CUSTOMER");
        assertThat(jdbc.queryForObject(
                "SELECT unit_price FROM quotation_lines WHERE tenant_id = ? AND id = ?",
                BigDecimal.class, tenantId, lineId)).isEqualByComparingTo("20.00");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE quotation_negotiation_history SET note = 'mutated' WHERE tenant_id = ? AND id = ?",
                tenantId, customerOffer.id())).isInstanceOf(RuntimeException.class);

        var revised = salesService.reviseQuotation(created.id(), new ReviseQuotationRequest(
                "New revision after negotiation",
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, new BigDecimal("3"), uomId, new BigDecimal("21.00"), 2))));
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.negotiationHistory()).isEmpty();
        assertThat(salesService.getQuotation(created.id()).negotiationHistory()).hasSize(3);
        assertThat(jdbc.queryForObject(
                "SELECT snapshot::text FROM quotation_revisions WHERE tenant_id = ? AND quotation_id = ?",
                String.class, tenantId, created.id())).contains("CUSTOMER", "TENANT");
    }

    @Test
    void quotationCannotBeReadAcrossTenants() {
        RfqResponse rfq = salesService.createRfq(new CreateRfqRequest(
                customerId,
                null,
                null,
                null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, null))));
        var created = salesService.createQuotation(new CreateQuotationRequest(
                customerId,
                rfq.id(),
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, BigDecimal.ONE, uomId, new BigDecimal("20.00"), null))));
        UUID otherTenant = provisionTenant("sales-other");
        setTenantContext(otherTenant, "OWNER");

        assertThatThrownBy(() -> salesService.getQuotation(created.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> salesService.getRfq(rfq.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @WithMockUser(roles = "STORE_KEEPER")
    void nonSalesRoleCannotInvokeSalesService() {
        assertThatThrownBy(() -> salesService.listRfqs(null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void quotationCostRemainsUnknownWhenBomAndQuotationUnitsDiffer() {
        UUID alternateUomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol)
                VALUES (?, 'Kilogram', 'kg') RETURNING id
                """, tenantId);
        var created = salesService.createQuotation(new CreateQuotationRequest(
                customerId,
                null,
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, BigDecimal.ONE, alternateUomId, new BigDecimal("20.00"), null))));

        assertThat(jdbc.queryForObject(
                "SELECT estimated_material_cost FROM quotations WHERE tenant_id = ? AND id = ?",
                BigDecimal.class, tenantId, created.id())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT estimated_material_cost_per_unit FROM quotation_lines WHERE tenant_id = ? AND quotation_id = ?",
                BigDecimal.class, tenantId, created.id())).isNull();
    }

    @Test
    void concurrentQuotationRevisionsSerializeOnTheSourceQuotation() throws Exception {
        var created = salesService.createQuotation(new CreateQuotationRequest(
                customerId,
                null,
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, BigDecimal.ONE, uomId, new BigDecimal("20.00"), null))));
        BossContext context = BossContextHolder.get();
        ReviseQuotationRequest revision = new ReviseQuotationRequest(
                "Concurrent revision",
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, BigDecimal.ONE, uomId, new BigDecimal("21.00"), null)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> reviseAtBarrier(created.id(), revision, context, ready, start));
            var second = executor.submit(() -> reviseAtBarrier(created.id(), revision, context, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS)).isNotEqualTo(second.get(20, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM quotations WHERE tenant_id = ? AND quotation_number = ? AND revision = 2",
                    Integer.class, tenantId, created.quotationNumber())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM quotation_revisions WHERE tenant_id = ? AND quotation_id = ?",
                    Integer.class, tenantId, created.id())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void salesPostReplayReturnsCachedResponseWithOriginalCreatedStatus() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateRfqRequest request = new CreateRfqRequest(
                customerId,
                null,
                null,
                null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, null)));
        String body = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/rfqs")
                        .with(user("sales-owner").roles("OWNER"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"));

        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/rfqs")
                        .with(user("sales-owner").roles("OWNER"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("draft"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rfqs WHERE tenant_id = ? AND customer_id = ?",
                Integer.class, tenantId, customerId)).isEqualTo(1);
    }

    @Test
    void ownerAcceptanceCreatesOneCustomerPoAndRejectionDoesNot() {
        var quotation = sentQuotation(new BigDecimal("20.00"));
        var first = customerPoService.accept(quotation.id(), new AcceptQuotationRequest("OFFLINE-14", "Confirmed by phone"));
        var second = customerPoService.accept(quotation.id(), new AcceptQuotationRequest("OFFLINE-14", "Confirmed by phone"));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.cpo().id()).isEqualTo(first.cpo().id());
        assertThat(first.cpo().quotationId()).isEqualTo(quotation.id());
        assertThat(first.cpo().quotationRevision()).isEqualTo(quotation.revision());
        assertThat(first.cpo().rfqId()).isEqualTo(quotation.rfqId());
        assertThat(first.cpo().status()).isEqualTo("open");
        assertThat(first.cpo().decisionChannel()).isEqualTo("OWNER_RECORDED");
        assertThat(first.cpo().decidedBy()).isEqualTo(BossContextHolder.get().userId());
        assertThat(first.cpo().lines().get(0).unitPrice()).isEqualByComparingTo("20.00");
        assertThat(first.cpo().lines().get(0).producedQty()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cpos WHERE tenant_id = ? AND quotation_id = ?",
                Integer.class, tenantId, quotation.id())).isEqualTo(1);
        assertThat(salesService.getQuotation(quotation.id()).status()).isEqualTo("accepted");
        assertThat(customerPoService.update(first.cpo().id(),
                new in.boss.sales.api.SalesApiDtos.UpdateCpoRequest("OFFLINE-15", "Updated reference", null, 45))
                .customerPoRef()).isEqualTo("OFFLINE-15");
        assertThat(customerPoService.get(first.cpo().id()).lines().get(0).unitPrice()).isEqualByComparingTo("20.00");
        assertThat(customerPoService.get(first.cpo().id()).lines().get(0).producedQty()).isEqualByComparingTo("0");

        var declined = sentQuotation(new BigDecimal("18.00"));
        salesService.updateQuotationStatus(declined.id(), "rejected", "Customer declined offline");
        assertThat(salesService.getQuotation(declined.id()).decisionChannel()).isEqualTo("OWNER_RECORDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cpos WHERE tenant_id = ? AND quotation_id = ?",
                Integer.class, tenantId, declined.id())).isZero();

        var waiting = sentQuotation(new BigDecimal("19.00"));
        assertThat(salesService.getQuotation(waiting.id()).status()).isEqualTo("sent");
        assertThat(waiting.cpoId()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cpos WHERE tenant_id = ? AND quotation_id = ?",
                Integer.class, tenantId, waiting.id())).isZero();
    }

    @Test
    void customerDeactivationKeepsTheRfq() {
        RfqResponse rfq = salesService.createRfq(new CreateRfqRequest(
                customerId, null, null, null,
                List.of(new RfqLineRequest(finishedItemId, BigDecimal.ONE, uomId, "keep"))));
        jdbc.update("UPDATE customers SET is_active = FALSE WHERE tenant_id = ? AND id = ?", tenantId, customerId);
        assertThat(salesService.getRfq(rfq.id()).customerId()).isEqualTo(customerId);
        assertThat(salesService.getRfq(rfq.id()).lines()).hasSize(1);
    }

    private in.boss.sales.api.SalesApiDtos.QuotationResponse sentQuotation(BigDecimal price) {
        RfqResponse rfq = salesService.createRfq(new CreateRfqRequest(
                customerId, null, null, null,
                List.of(new RfqLineRequest(finishedItemId, new BigDecimal("2"), uomId, null))));
        var draft = salesService.createQuotation(new CreateQuotationRequest(
                customerId, rfq.id(), null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, new BigDecimal("2"), uomId, price, 1,
                        null, null, "DRW-14", null, null, null))));
        return salesService.updateQuotationStatus(draft.id(), "sent");
    }

    private boolean reviseAtBarrier(
            UUID quotationId,
            ReviseQuotationRequest request,
            BossContext context,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        BossContextHolder.set(context);
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                "sales-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        SecurityContextHolder.setContext(securityContext);
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent revision");
            }
            salesService.reviseQuotation(quotationId, request);
            return true;
        } catch (ResponseStatusException e) {
            return false;
        } finally {
            BossContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }
}
