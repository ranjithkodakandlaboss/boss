package in.boss.procurement.api;

import in.boss.procurement.domain.IncomingInspectionService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static in.boss.procurement.api.ProcurementApiDtos.CompleteInspectionRequest;
import static in.boss.procurement.api.ProcurementApiDtos.InspectionResponse;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize(BossPermissions.QUALITY_WRITE)
public class IncomingInspectionController {

    private final IncomingInspectionService inspectionService;

    public IncomingInspectionController(IncomingInspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping("/grns/{grnId}/inspections")
    public List<InspectionResponse> list(@PathVariable UUID grnId) {
        return inspectionService.list(grnId);
    }

    @PostMapping("/grns/{grnId}/inspections")
    public InspectionResponse start(@PathVariable UUID grnId, @RequestParam UUID lotId) {
        return inspectionService.start(grnId, lotId);
    }

    @GetMapping("/quality-inspections/{inspectionId}")
    public InspectionResponse get(@PathVariable UUID inspectionId) {
        return inspectionService.get(inspectionId);
    }

    @PostMapping("/quality-inspections/{inspectionId}/complete")
    public InspectionResponse complete(@PathVariable UUID inspectionId,
                                       @Valid @RequestBody CompleteInspectionRequest request) {
        return inspectionService.complete(inspectionId, request);
    }
}
