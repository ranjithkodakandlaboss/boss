package in.boss.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.UomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MasterDataApiSecurityTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UomService uomService;

    private UUID tenantId;
    private UUID uomId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("api-sec-test");
        setTenantContext(tenantId, "OWNER");

        UomDto uom = uomService.createUom(CreateUomRequest.builder()
                .name("Each")
                .symbol("EA-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("quantity")
                .isBase(true)
                .build());
        uomId = uom.getId();
    }

    @Test
    void unauthenticatedRequest_rejected() throws Exception {
        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void fieldWorkerRole_cannotCreateItem_returns403() throws Exception {
        CreateItemRequest request = CreateItemRequest.builder()
                .code("ITEM-SEC-01")
                .name("Security Item")
                .itemType("RAW_MATERIAL")
                .uomId(uomId)
                .build();

        mockMvc.perform(post("/api/v1/items")
                        .with(user("field-worker").roles("FIELD_WORKER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerRole_canCreateItem_returns201_withIdempotencyKey() throws Exception {
        setTenantContext(tenantId, "OWNER");
        CreateItemRequest request = CreateItemRequest.builder()
                .code("ITEM-SEC-02")
                .name("Security Item Owner")
                .itemType("RAW_MATERIAL")
                .uomId(uomId)
                .build();

        mockMvc.perform(post("/api/v1/items")
                        .with(user("owner-user").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ITEM-SEC-02"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void getNonExistentItem_returns404ProblemDetail() throws Exception {
        setTenantContext(tenantId, "OWNER");
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/items/" + randomId)
                        .with(user("owner-user").roles("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }
}
