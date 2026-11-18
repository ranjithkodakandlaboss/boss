package in.boss.production.domain;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Production capabilities use the existing {@code tenant_entitlements} table.
 * A missing row is the platform default: enabled. A tenant row turns that capability off.
 */
@Service
public class ProductionFeatureFlags {

    public static final String JOB_CREATE = "production.job.create";
    public static final String ROUTING_STAGES = "production.routing.stages";
    public static final String STAGE_TRANSITION = "production.stage.transition";
    public static final String MATERIAL_ISSUE = "production.material.issue";
    public static final String MATERIAL_RETURN = "production.material.return";
    public static final String JOB_COSTING = "production.job.costing";
    public static final String INSPECTION_GATE = "production.inspection.gate";

    public static final List<String> ALL = List.of(
            JOB_CREATE, ROUTING_STAGES, STAGE_TRANSITION, MATERIAL_ISSUE,
            MATERIAL_RETURN, JOB_COSTING, INSPECTION_GATE);

    private final JdbcTemplate jdbc;

    public ProductionFeatureFlags(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean enabled(UUID tenantId, String feature) {
        Boolean stored = jdbc.query("""
                SELECT enabled
                FROM tenant_entitlements
                WHERE tenant_id = ? AND feature = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, rs -> rs.next() ? rs.getBoolean("enabled") : null, tenantId, feature);
        return stored == null || stored;
    }

    public void require(UUID tenantId, String feature) {
        if (!enabled(tenantId, feature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Production capability is disabled: " + feature);
        }
    }

    public Map<String, Boolean> snapshot(UUID tenantId) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (String feature : ALL) {
            flags.put(feature, enabled(tenantId, feature));
        }
        return flags;
    }
}
