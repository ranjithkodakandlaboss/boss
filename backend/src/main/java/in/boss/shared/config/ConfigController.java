package in.boss.shared.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Config options REST endpoint (issue #90).
 * GET /api/v1/config/options/{category} — returns active options for the caller's tenant.
 *
 * All dropdowns in the frontend must use this endpoint; no hard-coded lists.
 */
@RestController
@RequestMapping("/api/v1/config/options")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/categories")
    public List<ConfigCategoryDto> getCategories() {
        return configService.getCategories();
    }

    @GetMapping("/{category}")
    public List<ConfigOptionDto> getOptions(@PathVariable String category,
                                            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return configService.getOptions(category.toUpperCase(), includeInactive);
    }

    @PostMapping("/{category}")
    public ResponseEntity<ConfigOptionDto> createOption(
            @PathVariable String category,
            @RequestBody CreateOptionRequest req) {
        ConfigOptionDto created = configService.createOption(
                category.toUpperCase(), req.code(), req.label(), req.sortOrder());
        return ResponseEntity.status(201).body(created);
    }

    public record CreateOptionRequest(String code, String label, int sortOrder) {}

    @PutMapping("/{category}/{optionId}")
    public ConfigOptionDto updateOption(@PathVariable String category,
                                        @PathVariable UUID optionId,
                                        @RequestBody UpdateOptionRequest req) {
        return configService.updateOption(category.toUpperCase(), optionId, req.label());
    }

    public record UpdateOptionRequest(String label) {}

    @PutMapping("/{category}/order")
    public ResponseEntity<Map<String, String>> reorderOptions(
            @PathVariable String category,
            @RequestBody ReorderOptionsRequest req) {
        configService.reorderOptions(category.toUpperCase(), req.optionIds());
        return ResponseEntity.ok(Map.of("status", "reordered"));
    }

    public record ReorderOptionsRequest(List<UUID> optionIds) {}

    @PatchMapping("/{category}/{optionId}/status")
    public ResponseEntity<Map<String, String>> setOptionStatus(
            @PathVariable String category,
            @PathVariable UUID optionId,
            @RequestBody SetOptionStatusRequest req) {
        configService.setOptionActive(category.toUpperCase(), optionId, req.active());
        return ResponseEntity.ok(Map.of("status", req.active() ? "active" : "deactivated"));
    }

    public record SetOptionStatusRequest(boolean active) {}

    @DeleteMapping("/{category}/{optionId}")
    public ResponseEntity<Map<String, String>> deactivateOption(
            @PathVariable String category,
            @PathVariable UUID optionId) {
        configService.setOptionActive(category.toUpperCase(), optionId, false);
        return ResponseEntity.ok(Map.of("status", "deactivated"));
    }
}
