package in.boss.sales;

import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.sales.api.SalesApiDtos.CreateQuotationLineRequest;
import in.boss.sales.api.SalesApiDtos.CreateQuotationRequest;
import in.boss.sales.api.SalesApiDtos.CreateRfqRequest;
import in.boss.sales.api.SalesApiDtos.CounterOfferRequest;
import in.boss.sales.api.SalesApiDtos.OfferedUnitPrice;
import in.boss.sales.api.SalesApiDtos.ReviseQuotationRequest;
import in.boss.sales.api.SalesApiDtos.RfqLineRequest;
import in.boss.sales.api.SalesApiDtos.RfqResponse;
import in.boss.sales.api.SalesApiDtos.UpdateRfqRequest;
import in.boss.sales.api.SalesApiDtos.AcceptQuotationRequest;
import in.boss.sales.domain.CustomerPoService;
import in.boss.sales.domain.SalesService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class SalesApiIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private SalesService salesService;

    @Autowired
    private CustomerPoService customerPoService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID customerId;
    private UUID siteId;
    private UUID uomId;
    private UUID materialItemId;
    private UUID finishedItemId;

    @BeforeEach
    void setUpSalesFixtures() {
        tenantId = provisionTenant("sales-test");
        setTenantContext(tenantId, "OWNER");
        UUID userId = BossContextHolder.get().userId();
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Sales Test Entity', 'Sales Test Entity Ltd') RETURNING id
                """, tenantId);
        siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Sales Test Site', ?) RETURNING id
                """, tenantId, legalEntityId, "SITE-" + UUID.randomUUID().toString().substring(0, 8));
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
    void quotationNegotiationAlternatesOffersAndKeepsHistoryAppendOnly() {
        var created = salesService.createQuotation(new CreateQuotationRequest(
                customerId,
                null,
                null,
                List.of(new CreateQuotationLineRequest(
                        finishedItemId, new BigDecimal("3"), uomId, new BigDecimal("20.00"), 2))));
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
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cpos WHERE tenant_id = ? AND quotation_id = ?",
                Integer.class, tenantId, quotation.id())).isEqualTo(1);
        assertThat(salesService.getQuotation(quotation.id()).status()).isEqualTo("accepted");
        assertThat(customerPoService.update(first.cpo().id(),
                new in.boss.sales.api.SalesApiDtos.UpdateCpoRequest("OFFLINE-15", "Updated reference", null, 45))
                .customerPoRef()).isEqualTo("OFFLINE-15");
        assertThat(customerPoService.get(first.cpo().id()).lines().get(0).unitPrice()).isEqualByComparingTo("20.00");

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
                List.of(new CreateQuotationLineRequest(finishedItemId, new BigDecimal("2"), uomId, price, 1))));
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
