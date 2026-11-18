package in.boss.inventory.domain;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryFeatureFlags {

    public static final String JOB_RESERVATIONS = "inventory.job.reservations";
    public static final String CUSTOMER_PO_RESERVATIONS = "inventory.customer_po.reservations";
    public static final String MATERIAL_ISSUE = "inventory.material.issue";
    public static final String MATERIAL_RETURN = "inventory.material.return";
    public static final String REPLENISHMENT = "inventory.replenishment";
    public static final String TRANSFERS = "inventory.transfers";
    public static final List<String> ALL = List.of(
            JOB_RESERVATIONS, CUSTOMER_PO_RESERVATIONS, MATERIAL_ISSUE, MATERIAL_RETURN,
            REPLENISHMENT, TRANSFERS);

    private final JdbcTemplate jdbc;

    public InventoryFeatureFlags(JdbcTemplate jdbc) {
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
                    "Inventory capability is disabled: " + feature);
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
