package in.boss.inventory.api;

import in.boss.inventory.api.InventoryApiDtos.AddMovementRequest;
import in.boss.inventory.api.InventoryApiDtos.CreateLotRequest;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.InventoryPositionResponse;
import in.boss.inventory.api.InventoryApiDtos.MaterialLotResponse;
import in.boss.inventory.api.InventoryApiDtos.StockMovementResponse;
import in.boss.inventory.domain.InventoryService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
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
