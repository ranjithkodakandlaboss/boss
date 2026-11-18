package in.boss.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.domain.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InventoryLotsApiTest extends InventoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryService inventoryService;

    private Seed seed;

    @BeforeEach
    void setUp() {
        seed = seed("lot-api");
    }

    @Test
    void postLot_requiresIdempotencyKey_andReplaysTheOriginalLot() throws Exception {
        CreateLotRequest request = new CreateLotRequest(
                seed.itemId(), seed.storeId(), new BigDecimal("10"), new BigDecimal("2.50"),
                "BATCH", null, "available", null);
        String body = objectMapper.writeValueAsString(request);
        String key = UUID.randomUUID().toString();

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("available"))
                .andExpect(jsonPath("$.batchRef").value("BATCH"))
                .andExpect(jsonPath("$.lotNumber").value(org.hamcrest.Matchers.startsWith("LOT-")));

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.batchRef").value("BATCH"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM material_lots WHERE tenant_id = ?",
                Integer.class, seed.tenantId())).isEqualTo(1);

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLotRequest(
                                seed.itemId(), seed.storeId(), new BigDecimal("11"), new BigDecimal("2.50"),
                                "BATCH", null, "available", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void postMovement_requiresIdempotencyKey_andAppendsOnce() throws Exception {
        asKeeper();
        var lot = inventoryService.createLot(new CreateLotRequest(
                seed.itemId(), seed.storeId(), new BigDecimal("5"), new BigDecimal("1"),
                null, null, null, null));
        String body = objectMapper.writeValueAsString(
                new AddMovementRequest("receipt", new BigDecimal("2"), "GRN", UUID.randomUUID(), "top up"));
        String key = UUID.randomUUID().toString();

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots/" + lot.id() + "/movements")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots/" + lot.id() + "/movements")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("receipt"))
                .andExpect(jsonPath("$.referenceType").value("GRN"));

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots/" + lot.id() + "/movements")
                        .with(user("keeper").roles("STORE_KEEPER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"));

        asKeeper();
        assertThat(inventoryService.getLot(lot.id()).qty()).isEqualByComparingTo("7.000");
        assertThat(inventoryService.getLotMovements(lot.id(), null, null, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void reads_honorRoleAndTenantBoundaries() throws Exception {
        asKeeper();
        UUID grnLineId = insertGrnLine(seed, seed.itemId(), seed.storeId());
        var lot = inventoryService.createLot(new CreateLotRequest(
                seed.itemId(), seed.storeId(), new BigDecimal("3"), new BigDecimal("4"),
                null, null, "quarantine", grnLineId));

        mockMvc.perform(get("/api/v1/inventory/positions"))
                .andExpect(status().isUnauthorized());

        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/lots/" + lot.id())
                        .with(user("keeper").roles("STORE_KEEPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("quarantine"))
                .andExpect(jsonPath("$.grnLineId").value(lot.grnLineId().toString()));

        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/lots")
                        .with(user("supervisor").roles("PRODUCTION_SUPERVISOR"))
                        .param("status", "quarantine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/lots/" + lot.id() + "/movements")
                        .with(user("supervisor").roles("PRODUCTION_SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].movementType").value("receipt"));

        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/positions")
                        .with(user("supervisor").roles("PRODUCTION_SUPERVISOR"))
                        .param("itemId", seed.itemId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        asKeeper();
        mockMvc.perform(post("/api/v1/inventory/lots")
                        .with(user("supervisor").roles("PRODUCTION_SUPERVISOR"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLotRequest(
                                seed.itemId(), seed.storeId(), BigDecimal.ONE, BigDecimal.ONE,
                                null, null, null, null))))
                .andExpect(status().isForbidden());

        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/lots")
                        .with(user("field").roles("FIELD_WORKER")))
                .andExpect(status().isForbidden());

        UUID otherTenant = provisionTenant("lot-api-other");
        setTenantContext(otherTenant, "OWNER");
        mockMvc.perform(get("/api/v1/inventory/lots/" + lot.id())
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Lot Not Found"));
    }

    @Test
    void listReservations_isEmptyUntilAJobReservesStock() throws Exception {
        asKeeper();
        mockMvc.perform(get("/api/v1/inventory/reservations")
                        .with(user("keeper").roles("STORE_KEEPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    private void asKeeper() {
        setTenantContext(seed.tenantId(), "STORE_KEEPER");
    }
}
