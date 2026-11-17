package in.boss.procurement.api;

import in.boss.procurement.domain.PurchaseRequisitionService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static in.boss.procurement.api.ProcurementApiDtos.CreatePrRequest;
import static in.boss.procurement.api.ProcurementApiDtos.PageResponse;
import static in.boss.procurement.api.ProcurementApiDtos.PurchaseRequisitionResponse;
import static in.boss.procurement.api.ProcurementApiDtos.RejectPrRequest;
import static in.boss.procurement.api.ProcurementApiDtos.UpdatePrRequest;

@RestController
@RequestMapping("/api/v1/prs")
@PreAuthorize(BossPermissions.PR_ACCESS)
public class PurchaseRequisitionController {

    private final PurchaseRequisitionService prService;

    public PurchaseRequisitionController(PurchaseRequisitionService prService) {
        this.prService = prService;
    }

    @GetMapping
    public PageResponse<PurchaseRequisitionResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String originType,
            @RequestParam(required = false) UUID customerPoId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return prService.listPrs(status, originType, customerPoId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseRequisitionResponse create(@Valid @RequestBody CreatePrRequest request) {
        return prService.createPr(request);
    }

    @GetMapping("/{prId}")
    public PurchaseRequisitionResponse get(@PathVariable UUID prId) {
        return prService.getPr(prId);
    }

    @PutMapping("/{prId}")
    public PurchaseRequisitionResponse updateDraft(
            @PathVariable UUID prId,
            @Valid @RequestBody UpdatePrRequest request) {
        return prService.updateDraftPr(prId, request);
    }

    @PostMapping("/{prId}/submit")
    public PurchaseRequisitionResponse submit(@PathVariable UUID prId) {
        return prService.submitPr(prId);
    }

    @PostMapping("/{prId}/approve")
    @PreAuthorize(BossPermissions.PR_APPROVE)
    public PurchaseRequisitionResponse approve(@PathVariable UUID prId) {
        return prService.approvePr(prId);
    }

    @PostMapping("/{prId}/reject")
    @PreAuthorize(BossPermissions.PR_APPROVE)
    public PurchaseRequisitionResponse reject(
            @PathVariable UUID prId,
            @Valid @RequestBody RejectPrRequest request) {
        return prService.rejectPr(prId, request);
    }

    @PostMapping("/{prId}/cancel")
    public PurchaseRequisitionResponse cancel(@PathVariable UUID prId) {
        return prService.cancelPr(prId);
    }

    @PostMapping("/{prId}/recalculate-coverage")
    public PurchaseRequisitionResponse recalculateCoverage(@PathVariable UUID prId) {
        return prService.recalculateCoverage(prId);
    }
}
