package in.boss.sales.api;

import in.boss.sales.domain.CustomerPoService;
import in.boss.sales.domain.SalesService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static in.boss.sales.api.SalesApiDtos.AcceptQuotationRequest;
import static in.boss.sales.api.SalesApiDtos.CreateQuotationRequest;
import static in.boss.sales.api.SalesApiDtos.CounterOfferRequest;
import static in.boss.sales.api.SalesApiDtos.CpoResponse;
import static in.boss.sales.api.SalesApiDtos.PageResponse;
import static in.boss.sales.api.SalesApiDtos.QuotationResponse;
import static in.boss.sales.api.SalesApiDtos.QuotationStatusRequest;
import static in.boss.sales.api.SalesApiDtos.ReviseQuotationRequest;
import static in.boss.sales.api.SalesApiDtos.UpdateQuotationRequest;

@RestController
@RequestMapping("/api/v1/quotations")
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class SalesQuotationController {

    private final SalesService salesService;
    private final CustomerPoService customerPoService;

    public SalesQuotationController(SalesService salesService, CustomerPoService customerPoService) {
        this.salesService = salesService;
        this.customerPoService = customerPoService;
    }

    @GetMapping
    public PageResponse<QuotationResponse> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID rfqId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return salesService.listQuotations(customerId, rfqId, status, q, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuotationResponse create(@Valid @RequestBody CreateQuotationRequest request) {
        return salesService.createQuotation(request);
    }

    @GetMapping("/{quotationId}")
    public QuotationResponse get(@PathVariable UUID quotationId) {
        return salesService.getQuotation(quotationId);
    }

    @PutMapping("/{quotationId}")
    public QuotationResponse updateDraft(
            @PathVariable UUID quotationId,
            @Valid @RequestBody UpdateQuotationRequest request) {
        return salesService.updateDraftQuotation(quotationId, request);
    }

    @GetMapping(value = "/{quotationId}/document", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> document(@PathVariable UUID quotationId) {
        byte[] pdf = salesService.quotationPdf(quotationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-" + quotationId + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/{quotationId}/accept")
    public ResponseEntity<CpoResponse> accept(
            @PathVariable UUID quotationId,
            @Valid @RequestBody(required = false) AcceptQuotationRequest request) {
        CustomerPoService.AcceptResult result = customerPoService.accept(
                quotationId, request == null ? new AcceptQuotationRequest(null, null) : request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.cpo());
    }

    @PostMapping("/{quotationId}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    public QuotationResponse revise(
            @PathVariable UUID quotationId,
            @Valid @RequestBody ReviseQuotationRequest request) {
        return salesService.reviseQuotation(quotationId, request);
    }

    @PostMapping("/{quotationId}/counter-offer")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesApiDtos.NegotiationEntryResponse recordCounterOffer(
            @PathVariable UUID quotationId,
            @Valid @RequestBody CounterOfferRequest request) {
        return salesService.recordCounterOffer(quotationId, request);
    }

    @PostMapping("/{quotationId}/respond")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesApiDtos.NegotiationEntryResponse respondToCounterOffer(
            @PathVariable UUID quotationId,
            @Valid @RequestBody CounterOfferRequest request) {
        return salesService.respondToCounterOffer(quotationId, request);
    }

    @PatchMapping("/{quotationId}/status")
    public QuotationResponse updateStatus(
            @PathVariable UUID quotationId,
            @Valid @RequestBody QuotationStatusRequest request) {
        return salesService.updateQuotationStatus(quotationId, request.status(), request.note());
    }
}
