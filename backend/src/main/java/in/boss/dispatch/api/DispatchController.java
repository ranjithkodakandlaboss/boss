package in.boss.dispatch.api;

import in.boss.dispatch.api.DispatchDtos.*;
import in.boss.dispatch.domain.DispatchService;
import in.boss.dispatch.domain.PackingSpecService;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DispatchController {

    private final DispatchService service;
    private final PackingSpecService packingSpecs;

    public DispatchController(DispatchService service, PackingSpecService packingSpecs) {
        this.service = service;
        this.packingSpecs = packingSpecs;
    }

    // ==========================================
    // DISPATCHES
    // ==========================================

    @GetMapping("/dispatches")
    @PreAuthorize(BossPermissions.DISPATCH_VIEW)
    public List<DispatchSummary> listDispatches(
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return service.listDispatches(siteId, status, q);
    }

    @GetMapping("/dispatches/{id}")
    @PreAuthorize(BossPermissions.DISPATCH_VIEW)
    public DispatchDetail getDispatch(@PathVariable UUID id) {
        return service.getDispatch(id);
    }

    @PostMapping("/dispatches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.DISPATCH_WRITE)
    public DispatchDetail createDispatch(@RequestBody CreateDispatchRequest request) {
        return service.createDispatch(request);
    }

    @PatchMapping("/dispatches/{id}/status")
    @PreAuthorize(BossPermissions.DISPATCH_WRITE)
    public DispatchDetail updateStatus(
            @PathVariable UUID id,
            @RequestBody UpdateStatusRequest request) {
        return service.updateStatus(id, request);
    }

    @PostMapping("/dispatches/{id}/pod")
    @PreAuthorize(BossPermissions.DISPATCH_WRITE)
    public DispatchDetail recordPoD(
            @PathVariable UUID id,
            @RequestBody RecordPodRequest request) {
        return service.recordPoD(id, request);
    }

    @DeleteMapping("/dispatches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(BossPermissions.DISPATCH_WRITE)
    public void deleteDispatch(@PathVariable UUID id) {
        service.deleteDispatch(id);
    }

    @GetMapping(value = "/dispatches/{id}/challan", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize(BossPermissions.DISPATCH_VIEW)
    public ResponseEntity<String> getDeliveryChallan(@PathVariable UUID id) {
        var dispatch = service.getDispatch(id);
        StringBuilder sb = new StringBuilder();
        sb.append("====================================================\n");
        sb.append("                 DELIVERY CHALLAN                   \n");
        sb.append("====================================================\n");
        sb.append("Challan No:      ").append(dispatch.challanNumber()).append("\n");
        sb.append("Dispatch No:     ").append(dispatch.dispatchNumber()).append("\n");
        sb.append("Dispatch Date:   ").append(dispatch.dispatchDate()).append("\n");
        sb.append("Customer:        ").append(dispatch.customerName()).append("\n");
        sb.append("Carrier:         ").append(dispatch.carrier() != null ? dispatch.carrier() : "—").append("\n");
        sb.append("Vehicle No:      ").append(dispatch.vehicleNumber() != null ? dispatch.vehicleNumber() : "—").append("\n");
        sb.append("LR / Consign No: ").append(dispatch.lrNumber() != null ? dispatch.lrNumber() : "—").append("\n");
        sb.append("Total Quantity:  ").append(dispatch.totalQty()).append("\n");
        sb.append("Status:          ").append(dispatch.status()).append("\n");
        sb.append("----------------------------------------------------\n");
        sb.append("PACKAGES:\n");
        for (var pkg : dispatch.packages()) {
            sb.append(" * ").append(pkg.packageCode()).append(" (").append(pkg.packageType()).append("): ")
                    .append(pkg.quantity()).append(" ").append(pkg.uom() != null ? pkg.uom() : "units")
                    .append(", Weight: ").append(pkg.weightKg() != null ? pkg.weightKg() + " kg" : "—").append("\n");
        }
        sb.append("====================================================\n");
        return ResponseEntity.ok(sb.toString());
    }

    // ==========================================
    // PACKING SPECIFICATIONS
    // ==========================================

    @GetMapping("/packing-specs")
    @PreAuthorize(BossPermissions.DISPATCH_VIEW)
    public PagedPackingSpecs listPackingSpecs(
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return packingSpecs.listPackingSpecs(itemId, isActive, page, size);
    }

    @PostMapping("/packing-specs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.DISPATCH_WRITE)
    public PackingSpecDto createPackingSpec(@RequestBody CreatePackingSpecRequest request) {
        return packingSpecs.createPackingSpec(request);
    }

    @GetMapping("/packing-specs/{id}")
    @PreAuthorize(BossPermissions.DISPATCH_VIEW)
    public PackingSpecDto getPackingSpec(@PathVariable UUID id) {
        return packingSpecs.getPackingSpec(id);
    }

    @PutMapping("/packing-specs/{id}")
    @PreAuthorize(BossPermissions.DISPATCH_WRITE)
    public PackingSpecDto updatePackingSpec(
            @PathVariable UUID id,
            @RequestBody CreatePackingSpecRequest request) {
        return packingSpecs.updatePackingSpec(id, request);
    }

    @PostMapping("/dispatches/{dispatchId}/packing")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.DISPATCH_WRITE)
    public DispatchPackingRecordDto recordDispatchPacking(
            @PathVariable UUID dispatchId,
            @RequestBody RecordPackingRequest request) {
        return packingSpecs.recordDispatchPacking(dispatchId, request);
    }
}
