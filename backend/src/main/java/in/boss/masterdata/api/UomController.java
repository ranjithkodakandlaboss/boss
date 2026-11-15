package in.boss.masterdata.api;

import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.UomService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/uoms")
public class UomController {

    private final UomService uomService;

    public UomController(UomService uomService) {
        this.uomService = uomService;
    }

    @GetMapping
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<List<UomDto>> listUoms(@RequestParam(required = false) String uomType) {
        return ResponseEntity.ok(uomService.listUoms(uomType));
    }

    @GetMapping("/{id}")
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public ResponseEntity<UomDto> getUom(@PathVariable UUID id) {
        return ResponseEntity.ok(uomService.getUom(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.OWNER_OR_OPS)
    public ResponseEntity<UomDto> createUom(@Valid @RequestBody CreateUomRequest request) {
        UomDto created = uomService.createUom(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
