package in.boss.masterdata.api;

import in.boss.masterdata.api.dto.BOMExplosionResultDto;
import in.boss.masterdata.api.dto.BOMHeaderDto;
import in.boss.masterdata.api.dto.CreateBOMRequest;
import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.UpdateItemRequest;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.domain.BomService;
import in.boss.masterdata.domain.ItemService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemService itemService;
    private final BomService bomService;
    private final BomExplosionService bomExplosionService;

    public ItemController(ItemService itemService,
                          BomService bomService,
                          BomExplosionService bomExplosionService) {
        this.itemService = itemService;
        this.bomService = bomService;
        this.bomExplosionService = bomExplosionService;
    }

    @GetMapping
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<PageResponse<ItemDto>> listItems(
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(itemService.listItems(itemType, categoryId, status, q, page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.PURCHASE_ACCESS)
    public ResponseEntity<ItemDto> createItem(@Valid @RequestBody CreateItemRequest request) {
        ItemDto created = itemService.createItem(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{itemId}")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<ItemDto> getItem(@PathVariable UUID itemId) {
        return ResponseEntity.ok(itemService.getItem(itemId));
    }

    @PatchMapping("/{itemId}")
    @PreAuthorize(BossPermissions.INVENTORY_WRITE + " or " + BossPermissions.PURCHASE_ACCESS)
    public ResponseEntity<ItemDto> updateItem(
            @PathVariable UUID itemId,
            @RequestBody UpdateItemRequest request) {
        return ResponseEntity.ok(itemService.updateItem(itemId, request));
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ResponseEntity<Void> deleteItem(@PathVariable UUID itemId) {
        itemService.deleteItem(itemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{itemId}/bom")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<BOMHeaderDto> getItemBOM(@PathVariable UUID itemId) {
        return ResponseEntity.ok(bomService.getItemBOM(itemId));
    }

    @PostMapping("/{itemId}/bom")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.PRODUCTION_ACCESS)
    public ResponseEntity<BOMHeaderDto> createOrReviseBOM(
            @PathVariable UUID itemId,
            @Valid @RequestBody CreateBOMRequest request) {
        BOMHeaderDto header = bomService.createOrReviseBOM(itemId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(header);
    }

    @GetMapping("/{itemId}/bom/explode")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<BOMExplosionResultDto> explodeBOM(
            @PathVariable UUID itemId,
            @RequestParam(required = false) BigDecimal quantity) {
        return ResponseEntity.ok(bomExplosionService.explodeBOM(itemId, quantity));
    }
}
