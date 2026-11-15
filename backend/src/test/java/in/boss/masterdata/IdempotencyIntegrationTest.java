package in.boss.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.UomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class IdempotencyIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UomService uomService;

    private UUID tenantId;
    private UomDto baseUom;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("idemp-test");
        setTenantContext(tenantId, "OWNER");
        baseUom = uomService.createUom(CreateUomRequest.builder()
                .name("Piece")
                .symbol("PC-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("quantity")
                .build());
    }

    @Test
    void missingIdempotencyKey_onMutatingEndpoint_returns400() throws Exception {
        Map<String, Object> body = Map.of(
                "code", "ITEM-" + UUID.randomUUID().toString().substring(0, 5),
                "name", "Test Item",
                "itemType", "RAW_MATERIAL",
                "uomId", baseUom.getId().toString()
        );

        mockMvc.perform(post("/api/v1/items")
                        .with(user("test-user").roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void idempotencyKeyTooLong_returns400() throws Exception {
        String longKey = "a".repeat(129);
        Map<String, Object> body = Map.of(
                "code", "ITEM-" + UUID.randomUUID().toString().substring(0, 5),
                "name", "Test Item",
                "itemType", "RAW_MATERIAL",
                "uomId", baseUom.getId().toString()
        );

        mockMvc.perform(post("/api/v1/items")
                        .with(user("test-user").roles("OWNER"))
                        .header("Idempotency-Key", longKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void validKey_initialRequest_andReplay_returnsCachedResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        String itemCode = "ITEM-" + UUID.randomUUID().toString().substring(0, 5);
        Map<String, Object> body = Map.of(
                "code", itemCode,
                "name", "Original Item",
                "itemType", "RAW_MATERIAL",
                "uomId", baseUom.getId().toString()
        );

        // 1. Initial request -> 201 Created
        mockMvc.perform(post("/api/v1/items")
                        .with(user("test-user").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(itemCode))
                .andExpect(jsonPath("$.name").value("Original Item"));

        // 2. Duplicate replay with same key and same body -> returns cached 201 with header
        setTenantContext(tenantId, "OWNER");
        mockMvc.perform(post("/api/v1/items")
                        .with(user("test-user").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.code").value(itemCode))
                .andExpect(jsonPath("$.name").value("Original Item"));
    }

    @Test
    void replayWithDifferentPayload_returns422PayloadMismatch() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body1 = Map.of(
                "code", "ITEM-" + UUID.randomUUID().toString().substring(0, 5),
                "name", "First Payload",
                "itemType", "RAW_MATERIAL",
                "uomId", baseUom.getId().toString()
        );

        // Initial request succeeds
        mockMvc.perform(post("/api/v1/items")
                        .with(user("test-user").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body1)))
                .andExpect(status().isCreated());

        // Same key, different body -> 422 Unprocessable Entity
        setTenantContext(tenantId, "OWNER");
        Map<String, Object> body2 = Map.of(
                "code", "ITEM-" + UUID.randomUUID().toString().substring(0, 5),
                "name", "Second Different Payload",
                "itemType", "CONSUMABLE",
                "uomId", baseUom.getId().toString()
        );

        mockMvc.perform(post("/api/v1/items")
                        .with(user("test-user").roles("OWNER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }
}
