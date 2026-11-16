package in.boss.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrLineRequest;
import in.boss.procurement.api.ProcurementApiDtos.CreatePrRequest;
import in.boss.procurement.api.ProcurementApiDtos.PurchaseRequisitionResponse;
import in.boss.procurement.api.ProcurementApiDtos.UpdatePrRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class PrCrudTest extends BaseMasterDataIntegrationTest {

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

    @BeforeEach
    void setUpFixtures() {
        tenantId = provisionTenant("pr-crud-test");
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
                VALUES (?, 'Pieces', 'pcs', TRUE) RETURNING id
                """, tenantId);

        item1Id = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, std_cost)
                VALUES (?, ?, 'Item Alpha', 'raw_material', ?, 10.00) RETURNING id
                """, tenantId, "ITM-" + UUID.randomUUID().toString().substring(0, 8), uomId);

        item2Id = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, std_cost)
                VALUES (?, ?, 'Item Beta', 'raw_material', ?, 25.00) RETURNING id
                """, tenantId, "ITM-" + UUID.randomUUID().toString().substring(0, 8), uomId);
    }

    @Test
    void testCreateAndGetManualPr() {
        CreatePrRequest request = new CreatePrRequest(
                "manual",
                null,
                null,
                siteId,
                null,
                "Standard Purchase",
                LocalDate.now().plusDays(7),
                "high",
                "Urgent procurement for assembly line",
                List.of(
                        new CreatePrLineRequest(item1Id, BigDecimal.valueOf(50), null, uomId, BigDecimal.valueOf(10.0), null, null, "First line", null),
                        new CreatePrLineRequest(item2Id, BigDecimal.valueOf(20), null, uomId, BigDecimal.valueOf(25.0), null, null, "Second line", null)
                )
        );

        PurchaseRequisitionResponse created = prService.createPr(request);

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.prNumber()).startsWith("PR-");
        assertThat(created.status()).isEqualTo("draft");
        assertThat(created.originType()).isEqualTo("manual");
        assertThat(created.priority()).isEqualTo("high");
        assertThat(created.lines()).hasSize(2);
        assertThat(created.totalQty()).isEqualByComparingTo(BigDecimal.valueOf(70));
        assertThat(created.totalOrderedQty()).isEqualByComparingTo(BigDecimal.ZERO);

        PurchaseRequisitionResponse retrieved = prService.getPr(created.id());
        assertThat(retrieved.id()).isEqualTo(created.id());
        assertThat(retrieved.lines().get(0).itemCode()).isNotNull();
        assertThat(retrieved.lines().get(0).uomSymbol()).isEqualTo("pcs");
    }

    @Test
    void testUpdateDraftPr() {
        CreatePrRequest createReq = new CreatePrRequest(
                "manual",
                null,
                null,
                siteId,
                null,
                null,
                LocalDate.now().plusDays(5),
                "normal",
                "Initial note",
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, "Line 1", null))
        );

        PurchaseRequisitionResponse pr = prService.createPr(createReq);

        UpdatePrRequest updateReq = new UpdatePrRequest(
                siteId,
                null,
                "Updated Type",
                LocalDate.now().plusDays(10),
                "urgent",
                "Updated note",
                List.of(
                        new CreatePrLineRequest(item1Id, BigDecimal.valueOf(15), null, uomId, null, null, null, "Line 1 mod", null),
                        new CreatePrLineRequest(item2Id, BigDecimal.valueOf(30), null, uomId, null, null, null, "Line 2 new", null)
                )
        );

        PurchaseRequisitionResponse updated = prService.updateDraftPr(pr.id(), updateReq);

        assertThat(updated.priority()).isEqualTo("urgent");
        assertThat(updated.notes()).isEqualTo("Updated note");
        assertThat(updated.prTypeLabel()).isEqualTo("Updated Type");
        assertThat(updated.lines()).hasSize(2);
        assertThat(updated.totalQty()).isEqualByComparingTo(BigDecimal.valueOf(45));
    }

    @Test
    void testCannotUpdateNonDraftPr() {
        CreatePrRequest createReq = new CreatePrRequest(
                "manual",
                null,
                null,
                siteId,
                null,
                null,
                null,
                "normal",
                null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        );

        PurchaseRequisitionResponse pr = prService.createPr(createReq);
        prService.submitPr(pr.id());

        UpdatePrRequest updateReq = new UpdatePrRequest(
                siteId, null, null, null, "urgent", "Trying to update submitted", null
        );

        assertThatThrownBy(() -> prService.updateDraftPr(pr.id(), updateReq))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only draft purchase requisitions can be edited");
    }

    @Test
    void testListPrsWithFilters() {
        // Create 2 PRs
        prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        ));

        PurchaseRequisitionResponse pr2 = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "urgent", null,
                List.of(new CreatePrLineRequest(item2Id, BigDecimal.valueOf(20), null, uomId, null, null, null, null, null))
        ));
        prService.submitPr(pr2.id());

        var draftPage = prService.listPrs("draft", "manual", 0, 10);
        assertThat(draftPage.content()).isNotEmpty();
        assertThat(draftPage.content()).allMatch(p -> "draft".equals(p.status()));

        var submittedPage = prService.listPrs("submitted", null, 0, 10);
        assertThat(submittedPage.content()).isNotEmpty();
        assertThat(submittedPage.content()).allMatch(p -> "submitted".equals(p.status()));
    }

    @Test
    void testCancelDraftPrAndCannotCancelAgain() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        ));

        PurchaseRequisitionResponse cancelled = prService.cancelPr(pr.id());
        assertThat(cancelled.status()).isEqualTo("cancelled");

        assertThatThrownBy(() -> prService.cancelPr(pr.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot cancel purchase requisition in status cancelled");
    }

    @Test
    void testTenantIsolation() {
        PurchaseRequisitionResponse pr = prService.createPr(new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        ));

        // Switch to tenant B
        UUID tenantB = provisionTenant("pr-tenant-b");
        setTenantContext(tenantB, "OWNER");

        assertThatThrownBy(() -> prService.getPr(pr.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Purchase requisition not found");

        var listB = prService.listPrs(null, null, 0, 10);
        assertThat(listB.content()).noneMatch(p -> p.id().equals(pr.id()));
    }

    @Test
    void testRoleBasedAccessOnApiEndpoints() throws Exception {
        CreatePrRequest request = new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", null,
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        );
        String jsonBody = objectMapper.writeValueAsString(request);

        setTenantContext(tenantId, "FIELD_WORKER");
        // FIELD_WORKER is not allowed to create PR (403)
        mockMvc.perform(post("/api/v1/prs")
                        .with(user("worker").roles("FIELD_WORKER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isForbidden());

        // STORE_KEEPER is allowed to create PR (201)
        setTenantContext(tenantId, "STORE_KEEPER");
        mockMvc.perform(post("/api/v1/prs")
                        .with(user("storekeeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isCreated());
    }

    @Test
    void testIdempotencyOnPrCreation() throws Exception {
        String key = "PR-IDEMPOTENCY-" + UUID.randomUUID();
        CreatePrRequest request = new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", "Idempotent PR",
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        );
        String jsonBody = objectMapper.writeValueAsString(request);

        // First attempt -> 201 Created
        setTenantContext(tenantId, "OWNER");
        String response1 = mockMvc.perform(post("/api/v1/prs")
                        .with(user("owner-user").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        PurchaseRequisitionResponse pr1 = objectMapper.readValue(response1, PurchaseRequisitionResponse.class);

        // Replay identical request with same key -> 201 Replayed
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/prs")
                        .with(user("owner-user").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(pr1.id().toString()))
                .andExpect(jsonPath("$.prNumber").value(pr1.prNumber()));

        // Different payload with same key -> 409 Conflict
        CreatePrRequest differentRequest = new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "urgent", "Different payload",
                List.of(new CreatePrLineRequest(item2Id, BigDecimal.valueOf(99), null, uomId, null, null, null, null, null))
        );
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/prs")
                        .with(user("owner-user").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(differentRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void testControllerHttpEndpoints() throws Exception {
        CreatePrRequest request = new CreatePrRequest(
                "manual", null, null, siteId, null, null, null, "normal", "Controller test",
                List.of(new CreatePrLineRequest(item1Id, BigDecimal.valueOf(10), null, uomId, null, null, null, null, null))
        );
        String jsonBody = objectMapper.writeValueAsString(request);

        // POST /api/v1/prs
        setTenantContext(tenantId, "OWNER");
        String createRes = mockMvc.perform(post("/api/v1/prs")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        PurchaseRequisitionResponse pr = objectMapper.readValue(createRes, PurchaseRequisitionResponse.class);

        // GET /api/v1/prs/{prId}
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(get("/api/v1/prs/" + pr.id())
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pr.id().toString()));

        // GET /api/v1/prs
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(get("/api/v1/prs")
                        .with(user("owner").roles("OWNER"))
                        .param("status", "draft")
                        .param("originType", "manual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        // PUT /api/v1/prs/{prId}
        UpdatePrRequest updateReq = new UpdatePrRequest(siteId, null, null, null, "high", "Updated via controller", null);
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(put("/api/v1/prs/" + pr.id())
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("high"));

        // POST /api/v1/prs/{prId}/recalculate-coverage
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/prs/" + pr.id() + "/recalculate-coverage")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // POST /api/v1/prs/{prId}/submit
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/prs/" + pr.id() + "/submit")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"));

        // POST /api/v1/prs/{prId}/reject
        setTenantContext(tenantId, "PURCHASE_MANAGER");
        mockMvc.perform(post("/api/v1/prs/" + pr.id() + "/reject")
                        .with(user("pm").roles("PURCHASE_MANAGER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new in.boss.procurement.api.ProcurementApiDtos.RejectPrRequest("Rejected in test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"));

        // Create another PR to test /cancel
        setTenantContext(tenantId, "OWNER");
        String createRes2 = mockMvc.perform(post("/api/v1/prs")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        PurchaseRequisitionResponse pr2 = objectMapper.readValue(createRes2, PurchaseRequisitionResponse.class);

        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/prs/" + pr2.id() + "/cancel")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    private UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }
}
