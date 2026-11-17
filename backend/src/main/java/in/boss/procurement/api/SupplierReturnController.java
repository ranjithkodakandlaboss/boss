package in.boss.procurement.api;

import in.boss.procurement.domain.SupplierReturnService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static in.boss.procurement.api.ProcurementApiDtos.CreateSupplierReturnRequest;
import static in.boss.procurement.api.ProcurementApiDtos.SupplierReturnResponse;

@RestController
@RequestMapping("/api/v1/supplier-returns")
public class SupplierReturnController {

    private final SupplierReturnService supplierReturnService;

    public SupplierReturnController(SupplierReturnService supplierReturnService) {
        this.supplierReturnService = supplierReturnService;
    }

    @GetMapping("/{returnId}")
    public SupplierReturnResponse get(@PathVariable UUID returnId) {
        return supplierReturnService.get(returnId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierReturnResponse create(@Valid @RequestBody CreateSupplierReturnRequest request) {
        return supplierReturnService.create(request);
    }
}
