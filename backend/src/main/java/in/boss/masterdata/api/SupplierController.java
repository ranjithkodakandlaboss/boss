package in.boss.masterdata.api;

import in.boss.masterdata.api.dto.CreateSupplierRequest;
import in.boss.masterdata.api.dto.PageResponse;
import in.boss.masterdata.api.dto.SupplierDto;
import in.boss.masterdata.api.dto.UpdateSupplierRequest;
import in.boss.masterdata.domain.SupplierService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @PreAuthorize(BossPermissions.PURCHASE_ACCESS + " or " + BossPermissions.ACCOUNTS_ACCESS)
    public ResponseEntity<PageResponse<SupplierDto>> listSuppliers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(supplierService.listSuppliers(status, q, page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.PURCHASE_ACCESS)
    public ResponseEntity<SupplierDto> createSupplier(@Valid @RequestBody CreateSupplierRequest request) {
        SupplierDto created = supplierService.createSupplier(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{supplierId}")
    @PreAuthorize(BossPermissions.PURCHASE_ACCESS + " or " + BossPermissions.ACCOUNTS_ACCESS)
    public ResponseEntity<SupplierDto> getSupplier(@PathVariable UUID supplierId) {
        return ResponseEntity.ok(supplierService.getSupplier(supplierId));
    }

    @PatchMapping("/{supplierId}")
    @PreAuthorize(BossPermissions.PURCHASE_ACCESS)
    public ResponseEntity<SupplierDto> updateSupplier(
            @PathVariable UUID supplierId,
            @RequestBody UpdateSupplierRequest request) {
        return ResponseEntity.ok(supplierService.updateSupplier(supplierId, request));
    }

    @DeleteMapping("/{supplierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ResponseEntity<Void> deleteSupplier(@PathVariable UUID supplierId) {
        supplierService.deleteSupplier(supplierId);
        return ResponseEntity.noContent().build();
    }
}
