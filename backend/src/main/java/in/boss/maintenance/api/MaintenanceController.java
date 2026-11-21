package in.boss.maintenance.api;

import in.boss.maintenance.api.MaintenanceDtos.*;
import in.boss.maintenance.domain.MaintenanceService;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/maintenance")
@PreAuthorize(BossPermissions.MAINTENANCE_VIEW)
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping("/lookups")
    public Lookups lookups() { return service.lookups(); }

    @GetMapping("/assets")
    public List<AssetResponse> assets(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.listAssets(includeArchived);
    }

    @GetMapping("/assets/{id}")
    public AssetResponse asset(@PathVariable UUID id) { return service.getAsset(id); }

    @PostMapping("/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public AssetResponse create(@RequestBody AssetRequest request) { return service.createAsset(request); }

    @PutMapping("/assets/{id}")
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public AssetResponse update(@PathVariable UUID id, @RequestBody AssetRequest request) {
        return service.updateAsset(id, request);
    }

    @PutMapping("/assets/{id}/capabilities")
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public AssetResponse capabilities(@PathVariable UUID id, @RequestBody CapabilityRequest request) {
        return service.replaceCapabilities(id, request.stageOptionIds());
    }

    @PostMapping("/assets/{id}/archive")
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public AssetResponse archive(@PathVariable UUID id, @RequestBody ArchiveRequest request) {
        return service.archiveAsset(id, request);
    }

    @GetMapping("/assets/{id}/readings")
    public List<MeterReadingResponse> readings(@PathVariable UUID id) { return service.readings(id); }

    @PostMapping("/assets/{id}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public AssetResponse logReading(@PathVariable UUID id, @RequestBody MeterReadingRequest request) {
        return service.logReading(id, request);
    }

    @PostMapping("/assets/{id}/repair")
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public EventResponse repair(@PathVariable UUID id, @RequestBody(required = false) RepairRequest request) {
        return service.markRepaired(id, request);
    }

    @GetMapping("/events")
    public List<EventResponse> events(@RequestParam(required = false) UUID assetId,
                                      @RequestParam(required = false) UUID jobId) {
        return service.listEvents(assetId, jobId);
    }

    @GetMapping("/events/{id}")
    public EventResponse event(@PathVariable UUID id) { return service.getEvent(id); }

    @GetMapping("/events/{id}/activity")
    public List<ActivityEntry> eventActivity(@PathVariable UUID id) { return service.eventActivity(id); }

    @GetMapping("/assets/{id}/activity")
    public List<ActivityEntry> assetActivity(@PathVariable UUID id) { return service.assetActivity(id); }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public EventResponse createEvent(@RequestBody EventRequest request) { return service.createEvent(request); }

    @PostMapping("/events/{id}/start")
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public EventResponse start(@PathVariable UUID id) { return service.startEvent(id); }

    @PutMapping("/events/{id}/affected-jobs")
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public EventResponse addAffectedJobs(@PathVariable UUID id, @RequestBody AddAffectedJobsRequest request) {
        return service.addAffectedJobs(id, request);
    }

    @PostMapping("/events/{id}/close")
    @PreAuthorize(BossPermissions.MAINTENANCE_WRITE)
    public EventResponse close(@PathVariable UUID id, @RequestBody CloseEventRequest request) {
        return service.closeEvent(id, request);
    }
}
