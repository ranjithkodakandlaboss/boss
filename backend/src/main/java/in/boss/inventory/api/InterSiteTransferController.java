package in.boss.inventory.api;

import in.boss.inventory.api.InventoryApiDtos.CreateTransferRequest;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.ReceiveTransferRequest;
import in.boss.inventory.api.InventoryApiDtos.TransferResponse;
import in.boss.inventory.domain.InventoryTransferService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inter-site-transfers")
public class InterSiteTransferController {

    private final InventoryTransferService transferService;

    public InterSiteTransferController(InventoryTransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping
    @PreAuthorize(BossPermissions.INVENTORY_READ)
    public InventoryPage<TransferResponse> list(
            @RequestParam(required = false) UUID fromStoreId,
            @RequestParam(required = false) UUID toStoreId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transferService.list(fromStoreId, toStoreId, status, page, size);
    }

    @PostMapping
    @PreAuthorize(BossPermissions.INVENTORY_WRITE)
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateTransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transferService.create(request));
    }

    @PostMapping("/{transferId}/receive")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE)
    public TransferResponse receive(
            @PathVariable UUID transferId,
            @Valid @RequestBody(required = false) ReceiveTransferRequest request) {
        return transferService.receive(transferId,
                request == null ? new ReceiveTransferRequest(null) : request);
    }
}
