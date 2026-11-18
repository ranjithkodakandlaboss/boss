package in.boss.sales.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SalesApiDtos {

    private SalesApiDtos() {}

    public record RfqLineRequest(
            @NotNull UUID itemId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            @NotNull UUID uomId,
            @Size(max = 500) String customerSpecRef,
            @Size(max = 80) String drawingNumber,
            @Size(max = 20) String drawingRevision,
            @Size(max = 80) String material,
            @Size(max = 500) String description,
            @DecimalMin(value = "0.00") BigDecimal targetPrice,
            @Size(max = 500) String notes,
            LocalDate requiredBy) {
        @JsonIgnore
        public RfqLineRequest(UUID itemId, BigDecimal qty, UUID uomId, String customerSpecRef) {
            this(itemId, qty, uomId, customerSpecRef, null, null, null, null, null, null, null);
        }
    }

    public record CreateRfqRequest(
            @NotNull UUID customerId,
            UUID rfqTypeId,
            @Size(max = 200) String rfqTypeLabel,
            LocalDate requiredDate,
            @NotEmpty List<@Valid RfqLineRequest> lines,
            @Size(max = 120) String customerRef,
            @Size(max = 2000) String notes,
            @Size(max = 200) String deliveryTerms,
            @Min(0) @Max(365) Integer paymentTerms,
            @Size(max = 40) String receivedVia,
            LocalDate receivedDate,
            Map<String, Object> technicalRequirements) {
        @JsonIgnore
        public CreateRfqRequest(UUID customerId, UUID rfqTypeId, String rfqTypeLabel,
                                 LocalDate requiredDate, List<@Valid RfqLineRequest> lines) {
            this(customerId, rfqTypeId, rfqTypeLabel, requiredDate, lines, null, null, null, null, null, null, null);
        }
    }

    public record UpdateRfqRequest(
            UUID rfqTypeId,
            @Size(max = 200) String rfqTypeLabel,
            LocalDate requiredDate,
            @NotEmpty List<@Valid RfqLineRequest> lines,
            @Size(max = 120) String customerRef,
            @Size(max = 2000) String notes,
            @Size(max = 200) String deliveryTerms,
            @Min(0) @Max(365) Integer paymentTerms,
            @Size(max = 40) String receivedVia,
            LocalDate receivedDate,
            Map<String, Object> technicalRequirements) {
        @JsonIgnore
        public UpdateRfqRequest(UUID rfqTypeId, String rfqTypeLabel, LocalDate requiredDate,
                                 List<@Valid RfqLineRequest> lines) {
            this(rfqTypeId, rfqTypeLabel, requiredDate, lines, null, null, null, null, null, null, null);
        }
    }

    public record RfqStatusRequest(
            @NotNull @Pattern(regexp = "sent|closed|cancelled") String status) {}

    public record QuotationStatusRequest(
            @NotNull @Pattern(regexp = "sent|rejected|expired") String status,
            @Size(max = 500) String note) {
        @JsonIgnore
        public QuotationStatusRequest(String status) {
            this(status, null);
        }
    }

    public record RfqLineResponse(
            UUID id,
            int seq,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            UUID uomId,
            String uomSymbol,
            String customerSpecRef,
            String drawingNumber,
            String drawingRevision,
            String material,
            String description,
            BigDecimal targetPrice,
            String notes,
            LocalDate requiredBy) {}

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
            Instant createdAt,
            String customerRef,
            String notes,
            String deliveryTerms,
            Integer paymentTerms,
            String receivedVia,
            LocalDate receivedDate,
            String ownerName,
            Map<String, Object> technicalRequirements) {}

    public record CreateQuotationLineRequest(
            @NotNull UUID itemId,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qty,
            @NotNull UUID uomId,
            @NotNull @DecimalMin(value = "0.00") BigDecimal unitPrice,
            @Positive @Max(520) Integer deliveryWeeks,
            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal gstRate,
            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal discountPct,
            @Size(max = 80) String drawingNumber,
            @Size(max = 20) String drawingRevision,
            @Size(max = 500) String description,
            java.time.LocalDate requiredBy) {
        @JsonIgnore
        public CreateQuotationLineRequest(UUID itemId, BigDecimal qty, UUID uomId,
                                           BigDecimal unitPrice, Integer deliveryWeeks) {
            this(itemId, qty, uomId, unitPrice, deliveryWeeks, null, null, null, null, null, null);
        }
    }

    public record CreateQuotationRequest(
            @NotNull UUID customerId,
            UUID rfqId,
            LocalDate validUntil,
            @NotEmpty List<@Valid CreateQuotationLineRequest> lines,
            @Min(0) @Max(365) Integer paymentTerms,
            @Size(max = 200) String deliveryTerms,
            @Size(max = 2000) String notes,
            @DecimalMin("0.00") BigDecimal discountAmount,
            @Size(max = 200) String paymentTermsText) {
        @JsonIgnore
        public CreateQuotationRequest(UUID customerId, UUID rfqId, LocalDate validUntil,
                                       List<@Valid CreateQuotationLineRequest> lines) {
            this(customerId, rfqId, validUntil, lines, null, null, null, null, null);
        }
    }

    public record UpdateQuotationRequest(
            LocalDate validUntil,
            @NotEmpty List<@Valid CreateQuotationLineRequest> lines,
            @Min(0) @Max(365) Integer paymentTerms,
            @Size(max = 200) String deliveryTerms,
            @Size(max = 2000) String notes,
            @DecimalMin("0.00") BigDecimal discountAmount,
            @Size(max = 200) String paymentTermsText) {}

    public record ReviseQuotationRequest(
            @Size(max = 500) String changeReason,
            LocalDate validUntil,
            @NotEmpty List<@Valid CreateQuotationLineRequest> lines,
            @Min(0) @Max(365) Integer paymentTerms,
            @Size(max = 200) String deliveryTerms,
            @Size(max = 2000) String notes,
            @DecimalMin("0.00") BigDecimal discountAmount,
            @Size(max = 200) String paymentTermsText) {
        @JsonIgnore
        public ReviseQuotationRequest(String changeReason, LocalDate validUntil,
                                       List<@Valid CreateQuotationLineRequest> lines) {
            this(changeReason, validUntil, lines, null, null, null, null, null);
        }
    }

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
            Integer deliveryWeeks,
            BigDecimal gstRate,
            BigDecimal discountPct,
            String drawingNumber,
            String drawingRevision,
            String description,
            java.time.LocalDate requiredBy) {
        @JsonIgnore
        public QuotationLineResponse(UUID id, UUID itemId, String itemCode, String itemName,
                                     BigDecimal qty, UUID uomId, String uomSymbol, BigDecimal unitPrice,
                                     BigDecimal lineTotal, Integer deliveryWeeks) {
            this(id, itemId, itemCode, itemName, qty, uomId, uomSymbol, unitPrice, lineTotal,
                    deliveryWeeks, null, null, null, null, null, null);
        }
    }

    public record RevisionLink(UUID id, int revision, String status) {}

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
            Instant createdAt,
            Integer paymentTerms,
            String deliveryTerms,
            String notes,
            BigDecimal discountAmount,
            String decisionChannel,
            String decisionNote,
            UUID decidedBy,
            Instant decidedAt,
            UUID cpoId,
            String cpoNumber,
            String rfqNumber,
            List<RevisionLink> revisions,
            String paymentTermsText) {
        @JsonIgnore
        public QuotationResponse(UUID id, String quotationNumber, UUID rfqId, UUID customerId,
                                 String customerName, int revision, String status, LocalDate validUntil,
                                 List<QuotationLineResponse> lines, List<NegotiationEntryResponse> negotiationHistory,
                                 Instant createdAt) {
            this(id, quotationNumber, rfqId, customerId, customerName, revision, status, validUntil, lines,
                    negotiationHistory, createdAt, null, null, null, null, null, null, null, null, null, null, null, List.of(), null);
        }
    }

    public record AcceptQuotationRequest(
            @Size(max = 120) String customerPoRef,
            @Size(max = 500) String confirmationNote) {}

    public record CpoLineResponse(
            UUID id,
            UUID quotationLineId,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal qty,
            String uomSymbol,
            BigDecimal unitPrice,
            BigDecimal gstRate,
            BigDecimal discountPct,
            BigDecimal lineTotal,
            String description,
            String drawingNumber,
            String drawingRevision,
            String availability,
            BigDecimal shortageQty) {}

    public record CpoResponse(
            UUID id,
            String cpoNumber,
            UUID customerId,
            String customerName,
            UUID quotationId,
            String quotationNumber,
            Integer quotationRevision,
            UUID rfqId,
            String rfqNumber,
            String customerPoRef,
            String status,
            LocalDate orderDate,
            LocalDate requiredByDate,
            Integer paymentTerms,
            String notes,
            BigDecimal discountAmount,
            String decisionChannel,
            String decisionNote,
            UUID decidedBy,
            Instant decidedAt,
            List<CpoLineResponse> lines,
            Instant createdAt,
            String paymentTermsText) {}

    public record UpdateCpoRequest(
            @Size(max = 120) String customerPoRef,
            @Size(max = 2000) String notes,
            LocalDate requiredByDate,
            @Min(0) @Max(365) Integer paymentTerms) {}

    public record CpoStatusRequest(
            @NotNull @Pattern(regexp = "open|on_hold|feasibility_pending|feasibility_approved|cancelled")
            String status) {}

    public record RecordMaterialAvailabilityRequest(
            @NotEmpty List<@Valid AvailabilityLine> lines) {
        public record AvailabilityLine(
                @NotNull UUID lineId,
                @NotBlank @Pattern(regexp = "available|partial|unavailable") String availability,
                @DecimalMin("0.000") BigDecimal shortageQty) {}
    }

    public record FieldChange(String field, String previousValue, String newValue) {}

    public record AuditEntry(
            UUID id,
            UUID actorId,
            String actorName,
            String action,
            Instant createdAt,
            List<FieldChange> changes) {
        @JsonIgnore
        public AuditEntry(UUID id, UUID actorId, String action, Instant createdAt) {
            this(id, actorId, null, action, createdAt, List.of());
        }
    }

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements) {}
}
