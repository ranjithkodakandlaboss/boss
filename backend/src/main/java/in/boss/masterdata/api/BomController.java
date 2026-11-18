package in.boss.masterdata.api;

import in.boss.masterdata.api.dto.BOMHeaderDto;
import in.boss.masterdata.domain.BomService;
import in.boss.shared.security.BossPermissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/boms")
public class BomController {

    private final BomService bomService;

    public BomController(BomService bomService) {
        this.bomService = bomService;
    }

    @GetMapping
    @PreAuthorize(BossPermissions.ANY_TENANT_USER)
    public List<BOMHeaderDto> list() {
        return bomService.list();
    }
}
