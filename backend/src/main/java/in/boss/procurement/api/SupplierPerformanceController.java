package in.boss.procurement.api;

import in.boss.procurement.domain.SupplierPerformanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static in.boss.procurement.api.ProcurementApiDtos.SupplierPerformanceResponse;

@RestController
@RequestMapping("/api/v1/supplier-performance")
public class SupplierPerformanceController {

    private final SupplierPerformanceService performanceService;

    public SupplierPerformanceController(SupplierPerformanceService performanceService) {
        this.performanceService = performanceService;
    }

    @GetMapping
    public SupplierPerformanceResponse get(@RequestParam UUID supplierId) {
        return performanceService.forSupplier(supplierId);
    }
}
