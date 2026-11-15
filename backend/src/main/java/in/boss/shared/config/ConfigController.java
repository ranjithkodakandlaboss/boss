package in.boss.shared.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/{category}")
    public List<ConfigOptionDto> getOptions(@PathVariable String category) {
        return configService.getOptions(category.toUpperCase());
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

    @DeleteMapping("/{category}/{optionId}")
    public ResponseEntity<Map<String, String>> deactivateOption(
            @PathVariable String category,
            @PathVariable java.util.UUID optionId) {
        configService.deactivateOption(optionId);
        return ResponseEntity.ok(Map.of("status", "deactivated"));
    }
}
