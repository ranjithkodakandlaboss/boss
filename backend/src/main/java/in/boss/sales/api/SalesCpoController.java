package in.boss.sales.api;

import in.boss.sales.domain.CustomerPoService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static in.boss.sales.api.SalesApiDtos.CpoResponse;
import static in.boss.sales.api.SalesApiDtos.CpoStatusRequest;
import static in.boss.sales.api.SalesApiDtos.PageResponse;
import static in.boss.sales.api.SalesApiDtos.RecordMaterialAvailabilityRequest;
import static in.boss.sales.api.SalesApiDtos.UpdateCpoRequest;
import static in.boss.inventory.api.InventoryApiDtos.FeasibilityResponse;

@RestController
@RequestMapping("/api/v1/cpos")
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class SalesCpoController {

    private final CustomerPoService customerPoService;

    public SalesCpoController(CustomerPoService customerPoService) {
        this.customerPoService = customerPoService;
    }

    @GetMapping
    public PageResponse<CpoResponse> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID quotationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return customerPoService.list(customerId, quotationId, status, q, page, size);
    }

    @GetMapping("/{cpoId}")
    public CpoResponse get(@PathVariable UUID cpoId) {
        return customerPoService.get(cpoId);
    }

    @PutMapping("/{cpoId}")
    public CpoResponse update(@PathVariable UUID cpoId, @Valid @RequestBody UpdateCpoRequest request) {
        return customerPoService.update(cpoId, request);
    }

    @PostMapping("/{cpoId}/availability")
    public CpoResponse recordAvailability(
            @PathVariable UUID cpoId, @Valid @RequestBody RecordMaterialAvailabilityRequest request) {
        return customerPoService.recordAvailability(cpoId, request);
    }

    @PatchMapping("/{cpoId}/status")
    @PreAuthorize(BossPermissions.CPO_TRANSITIONS)
    public CpoResponse updateStatus(@PathVariable UUID cpoId, @Valid @RequestBody CpoStatusRequest request) {
        return customerPoService.updateStatus(cpoId, request.status());
    }

    @PostMapping("/{cpoId}/feasibility")
    @PreAuthorize(BossPermissions.CPO_TRANSITIONS)
    public FeasibilityResponse checkFeasibility(@PathVariable UUID cpoId) {
        return customerPoService.checkFeasibility(cpoId);
    }
}
