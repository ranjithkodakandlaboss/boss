package in.boss.procurement.api;

import in.boss.procurement.domain.SupplierQuotationService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static in.boss.procurement.api.ProcurementApiDtos.CreateSupplierQuotationRequest;
import static in.boss.procurement.api.ProcurementApiDtos.PageResponse;
import static in.boss.procurement.api.ProcurementApiDtos.ReceiveSupplierQuotationRequest;
import static in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationComparisonResponse;
import static in.boss.procurement.api.ProcurementApiDtos.SupplierQuotationResponse;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize(BossPermissions.PROCUREMENT_MANAGE)
public class SupplierQuotationController {

    private final SupplierQuotationService quotationService;

    public SupplierQuotationController(SupplierQuotationService quotationService) {
        this.quotationService = quotationService;
    }

    @GetMapping("/prs/{prId}/supplier-quotations")
    public PageResponse<SupplierQuotationResponse> list(
            @PathVariable UUID prId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return quotationService.listByPr(prId, page, size);
    }

    @PostMapping("/prs/{prId}/supplier-quotations")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierQuotationResponse request(
            @PathVariable UUID prId,
            @Valid @RequestBody CreateSupplierQuotationRequest request) {
        return quotationService.requestQuotation(prId, request);
    }

    @GetMapping("/prs/{prId}/supplier-quotations/comparison")
    public SupplierQuotationComparisonResponse compare(@PathVariable UUID prId) {
        return quotationService.compareByPr(prId);
    }

    @GetMapping("/supplier-quotations/{quotationId}")
    public SupplierQuotationResponse get(@PathVariable UUID quotationId) {
        return quotationService.getQuotation(quotationId);
    }

    @PostMapping("/supplier-quotations/{quotationId}/receive")
    public SupplierQuotationResponse receive(
            @PathVariable UUID quotationId,
            @Valid @RequestBody ReceiveSupplierQuotationRequest request) {
        return quotationService.receiveQuotation(quotationId, request);
    }

    @PostMapping("/supplier-quotations/{quotationId}/review")
    public SupplierQuotationResponse startReview(@PathVariable UUID quotationId) {
        return quotationService.startReview(quotationId);
    }

    @PostMapping("/supplier-quotations/{quotationId}/select")
    public SupplierQuotationResponse select(@PathVariable UUID quotationId) {
        return quotationService.selectQuotation(quotationId);
    }

    @PostMapping("/supplier-quotations/{quotationId}/reject")
    public SupplierQuotationResponse reject(@PathVariable UUID quotationId) {
        return quotationService.rejectQuotation(quotationId);
    }
}
