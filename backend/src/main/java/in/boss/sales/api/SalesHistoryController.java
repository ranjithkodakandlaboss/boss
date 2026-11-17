package in.boss.sales.api;

import in.boss.sales.api.SalesApiDtos.AuditEntry;
import in.boss.sales.domain.SalesService;
import in.boss.shared.security.BossPermissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales/history")
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class SalesHistoryController {

    private final SalesService salesService;

    public SalesHistoryController(SalesService salesService) {
        this.salesService = salesService;
    }

    @GetMapping
    public List<AuditEntry> list(@RequestParam String entityType, @RequestParam UUID entityId) {
        return salesService.listHistory(entityType, entityId);
    }
}
