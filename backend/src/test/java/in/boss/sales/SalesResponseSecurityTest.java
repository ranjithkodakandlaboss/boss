package in.boss.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.sales.api.SalesApiDtos.NegotiationEntryResponse;
import in.boss.sales.api.SalesApiDtos.OfferedUnitPrice;
import in.boss.sales.api.SalesApiDtos.QuotationLineResponse;
import in.boss.sales.api.SalesApiDtos.QuotationResponse;
import in.boss.sales.domain.SalesService;
import in.boss.shared.security.BossPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SalesResponseSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void quotationResponseOmitsRawStandardCostAndBomDetails() throws Exception {
        QuotationResponse response = new QuotationResponse(
                UUID.randomUUID(),
                "QT-20261116-ABCD1234",
                null,
                UUID.randomUUID(),
                "Example Customer",
                1,
                "draft",
                LocalDate.now().plusDays(30),
                List.of(new QuotationLineResponse(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "FG-1",
                        "Finished Item",
                        new BigDecimal("3"),
                        UUID.randomUUID(),
                        "ea",
                        new BigDecimal("20.00"),
                        new BigDecimal("60.00"),
                        2)),
                List.of(new NegotiationEntryResponse(
                        UUID.randomUUID(),
                        1,
                        "CUSTOMER",
                        List.of(new OfferedUnitPrice(UUID.randomUUID(), new BigDecimal("18.00"))),
                        null,
                        Instant.now())),
                Instant.now());

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(json.has("estimatedMaterialCost")).isFalse();
        assertThat(json.has("standardCost")).isFalse();
        assertThat(json.has("marginPct")).isFalse();
        assertThat(json.has("flatMaterials")).isFalse();
        assertThat(json.toString()).doesNotContain("rawBomQuantities", "std_cost");
    }

    @Test
    void salesServiceUsesOnlyTheApprovedSalesPermission() {
        PreAuthorize access = SalesService.class.getAnnotation(PreAuthorize.class);

        assertThat(access).isNotNull();
        assertThat(access.value()).isEqualTo(BossPermissions.SALES_ACCESS);
    }
}
