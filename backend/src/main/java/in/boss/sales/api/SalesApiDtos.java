package in.boss.sales.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SalesApiDtos {

    private SalesApiDtos() {}

    public record RfqLineRequest(
            @NotNull UUID itemId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            @NotNull UUID uomId,
            @Size(max = 500) String customerSpecRef) {}

    public record CreateRfqRequest(
            @NotNull UUID customerId,
            UUID rfqTypeId,
            @Size(max = 200) String rfqTypeLabel,
            LocalDate requiredDate,
            @NotEmpty List<@Valid RfqLineRequest> lines) {}

    public record UpdateRfqRequest(
            UUID rfqTypeId,
            @Size(max = 200) String rfqTypeLabel,
            LocalDate requiredDate,
            @NotEmpty List<@Valid RfqLineRequest> lines) {}

    public record RfqStatusRequest(
            @NotNull @Pattern(regexp = "sent|closed|cancelled") String status) {}

    public record QuotationStatusRequest(
            @NotNull @Pattern(regexp = "sent|rejected|expired") String status) {}

    public record RfqLineResponse(
            UUID id,
            int seq,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            UUID uomId,
            String uomSymbol,
            String customerSpecRef) {}

    public record RfqResponse(
            UUID id,
            String rfqNumber,
            UUID customerId,
            String customerName,
            UUID rfqTypeId,
            String rfqTypeLabel,
            String status,
            LocalDate requiredDate,
            List<RfqLineResponse> lines,
            Instant createdAt) {}

    public record CreateQuotationLineRequest(
            @NotNull UUID itemId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            @NotNull UUID uomId,
            @NotNull @DecimalMin(value = "0.00") BigDecimal unitPrice,
            @Positive @Max(520) Integer deliveryWeeks) {}

    public record CreateQuotationRequest(
            @NotNull UUID customerId,
            UUID rfqId,
            LocalDate validUntil,
            @NotEmpty List<@Valid CreateQuotationLineRequest> lines) {}

    public record ReviseQuotationRequest(
            @Size(max = 500) String changeReason,
            LocalDate validUntil,
            @NotEmpty List<@Valid CreateQuotationLineRequest> lines) {}

    public record OfferedUnitPrice(
            @NotNull UUID lineId,
            @NotNull @DecimalMin(value = "0.00") BigDecimal unitPrice) {}

    public record CounterOfferRequest(
            @NotEmpty List<@Valid OfferedUnitPrice> offeredUnitPrices,
            @Size(max = 500) String note) {}

    public record NegotiationEntryResponse(
            UUID id,
            int round,
            String partyType,
            List<OfferedUnitPrice> offeredUnitPrices,
            String note,
            Instant createdAt) {}

    public record QuotationLineResponse(
            UUID id,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            UUID uomId,
            String uomSymbol,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            Integer deliveryWeeks) {}

    public record QuotationResponse(
            UUID id,
            String quotationNumber,
            UUID rfqId,
            UUID customerId,
            String customerName,
            int revision,
            String status,
            LocalDate validUntil,
            List<QuotationLineResponse> lines,
            List<NegotiationEntryResponse> negotiationHistory,
            Instant createdAt) {}

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements) {}
}
