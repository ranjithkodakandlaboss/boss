package in.boss.inventory.api;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.CustomerPoReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.JobLotReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.JobReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.InventoryPositionResponse;
import in.boss.inventory.api.InventoryApiDtos.IssueMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.api.InventoryApiDtos.ReservationRequest;
import in.boss.inventory.api.InventoryApiDtos.ReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.ReorderPolicyRequest;
import in.boss.inventory.api.InventoryApiDtos.ReorderPolicyResponse;
import in.boss.inventory.api.InventoryApiDtos.ReplenishmentRequestResponse;
import in.boss.inventory.api.InventoryApiDtos.ReturnMaterialRequest;
import in.boss.inventory.api.InventoryApiDtos.StockMovementResponse;
import in.boss.inventory.domain.InventoryService;
import in.boss.inventory.domain.InventoryFeatureFlags;
import in.boss.inventory.domain.InventoryReplenishmentService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryFeatureFlags featureFlags;
    private final InventoryReplenishmentService replenishmentService;

    public InventoryController(
            InventoryService inventoryService,
            InventoryFeatureFlags featureFlags,
            InventoryReplenishmentService replenishmentService) {
        this.inventoryService = inventoryService;
        this.featureFlags = featureFlags;
        this.replenishmentService = replenishmentService;
    }

    @GetMapping("/features")
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public Map<String, Boolean> getFeatureFlags() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return featureFlags.snapshot(context.tenantId());
    }

    @GetMapping("/positions")
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public InventoryPage<InventoryPositionResponse> getInventoryPositions(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) Boolean belowReorderPoint,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return inventoryService.getPosition(
                storeId, itemId, siteId, Boolean.TRUE.equals(belowReorderPoint), page, size);
    }

    @PostMapping("/lots")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE)
    public ResponseEntity<MaterialLotResponse> createLot(@Valid @RequestBody CreateLotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createLot(request));
    }

    @GetMapping("/lots")
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public InventoryPage<MaterialLotResponse> listLots(
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return inventoryService.listLots(itemId, storeId, status, page, size);
    }

    @GetMapping("/lots/{lotId}")
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public MaterialLotResponse getLot(@PathVariable UUID lotId) {
        return inventoryService.getLot(lotId);
    }

    @PostMapping("/lots/{lotId}/movements")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE)
    public ResponseEntity<StockMovementResponse> addMovement(
            @PathVariable UUID lotId,
            @Valid @RequestBody AddMovementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.addMovement(lotId, request));
    }

    @GetMapping("/reservations/lots")
    @PreAuthorize(BossPermissions.INVENTORY_READ + " or " + BossPermissions.PRODUCTION_VIEW)
    public List<JobLotReservationResponse> listJobLotReservations(@RequestParam UUID jobId) {
        return inventoryService.listJobLotReservations(jobId);
    }

    @GetMapping("/reservations")
    @PreAuthorize(BossPermissions.INVENTORY_READ + " or " + BossPermissions.PRODUCTION_VIEW)
    public List<JobReservationResponse> listActiveJobReservations() {
        return inventoryService.listActiveJobReservations();
    }

    @PostMapping("/reservations")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.PRODUCTION_WRITE)
    public ResponseEntity<ReservationResponse> reserveForJob(
            @Valid @RequestBody ReservationRequest request,
            @RequestParam(defaultValue = "false") boolean partial) {
        ReservationResponse response = inventoryService.reserveForJob(request, partial);
        HttpStatus status = "FAILED".equals(response.status()) ? HttpStatus.CONFLICT : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/reservations/{jobId}/requirements")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.PRODUCTION_WRITE)
    public ResponseEntity<ReservationResponse> reserveJobRequirements(
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "false") boolean partial) {
        ReservationResponse response = inventoryService.reserveForJobRequirements(jobId, partial);
        HttpStatus status = "FAILED".equals(response.status()) ? HttpStatus.CONFLICT : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/reservations/customer-pos")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.SALES_WRITE)
    public ResponseEntity<CustomerPoReservationResponse> reserveForCustomerPo(
            @Valid @RequestBody CustomerPoReservationRequest request,
            @RequestParam(defaultValue = "false") boolean partial) {
        CustomerPoReservationResponse response = inventoryService.reserveForCustomerPo(request, partial);
        HttpStatus status = "FAILED".equals(response.status()) ? HttpStatus.CONFLICT : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @DeleteMapping("/reservations/customer-pos/{cpoId}")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.SALES_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseCustomerPoReservations(@PathVariable UUID cpoId) {
        inventoryService.releaseCustomerPoReservations(cpoId);
    }

    @DeleteMapping("/reservations/{jobId}")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.PRODUCTION_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseJobReservations(@PathVariable UUID jobId) {
        inventoryService.releaseJobReservations(jobId);
    }

    @PostMapping("/issues")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.PRODUCTION_WRITE)
    public ResponseEntity<StockMovementResponse> issueMaterial(
            @Valid @RequestBody IssueMaterialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.issueMaterial(request));
    }

    @PostMapping("/returns")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.PRODUCTION_WRITE)
    public ResponseEntity<StockMovementResponse> returnMaterial(
            @Valid @RequestBody ReturnMaterialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.returnMaterial(request));
    }

    @PostMapping("/reorder-policies")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE)
    public ReorderPolicyResponse upsertReorderPolicy(
            @Valid @RequestBody ReorderPolicyRequest request) {
        return replenishmentService.upsertPolicy(request);
    }

    @GetMapping("/reorder-policies")
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public InventoryPage<ReorderPolicyResponse> listReorderPolicies(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return replenishmentService.listPolicies(storeId, page, size);
    }

    @GetMapping("/replenishment-requests")
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public InventoryPage<ReplenishmentRequestResponse> listReplenishmentRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID itemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return replenishmentService.listRequests(status, storeId, itemId, page, size);
    }

    @PostMapping("/replenishment-requests/{requestId}/cancel")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE)
    public ReplenishmentRequestResponse cancelReplenishmentRequest(@PathVariable UUID requestId) {
        return replenishmentService.cancelRequest(requestId);
    }

    @GetMapping("/lots/{lotId}/movements")
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public InventoryPage<StockMovementResponse> getLotMovements(
            @PathVariable UUID lotId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return inventoryService.getLotMovements(lotId, from, to, page, size);
    }
}
