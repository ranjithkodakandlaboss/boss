package in.boss.sales.api;

import in.boss.sales.domain.SalesService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import static in.boss.sales.api.SalesApiDtos.CreateRfqRequest;
import static in.boss.sales.api.SalesApiDtos.PageResponse;
import static in.boss.sales.api.SalesApiDtos.RfqResponse;
import static in.boss.sales.api.SalesApiDtos.RfqStatusRequest;
import static in.boss.sales.api.SalesApiDtos.UpdateRfqRequest;

@RestController
@RequestMapping("/api/v1/rfqs")
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class SalesRfqController {

    private final SalesService salesService;

    public SalesRfqController(SalesService salesService) {
        this.salesService = salesService;
    }

    @GetMapping
    public PageResponse<RfqResponse> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return salesService.listRfqs(customerId, status, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RfqResponse create(@Valid @RequestBody CreateRfqRequest request) {
        return salesService.createRfq(request);
    }

    @GetMapping("/{rfqId}")
    public RfqResponse get(@PathVariable UUID rfqId) {
        return salesService.getRfq(rfqId);
    }

    @PutMapping("/{rfqId}")
    public RfqResponse updateDraft(
            @PathVariable UUID rfqId,
            @Valid @RequestBody UpdateRfqRequest request) {
        return salesService.updateDraftRfq(rfqId, request);
    }

    @PatchMapping("/{rfqId}/status")
    public RfqResponse updateStatus(
            @PathVariable UUID rfqId,
            @Valid @RequestBody RfqStatusRequest request) {
        return salesService.updateRfqStatus(rfqId, request.status());
    }
}
