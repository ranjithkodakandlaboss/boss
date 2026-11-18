package in.boss.shared.config;

import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class ConfigOptionOrderingService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ConfigOptionOrderingService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public void reorderOptions(String categoryCode, List<UUID> optionIds) {
        BossContext context = requireContext();
        requireOwner(context);
        requireTenantEditable(categoryCode);
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Item categories do not support custom ordering");
        }
        List<UUID> currentIds = jdbc.query("""
                SELECT o.id FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND o.is_active = TRUE AND o.is_system = FALSE
                ORDER BY o.sort_order, o.label
                """, (rs, row) -> rs.getObject("id", UUID.class), context.tenantId(), categoryCode.toUpperCase());
        if (optionIds == null || optionIds.size() != new HashSet<>(optionIds).size()
                || !new HashSet<>(currentIds).equals(new HashSet<>(optionIds))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reordering must include every active tenant-managed option exactly once");
        }
        Integer systemMaxOrder = jdbc.queryForObject("""
                SELECT COALESCE(MAX(o.sort_order), 0)
                FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND o.is_system = TRUE
                """, Integer.class, context.tenantId(), categoryCode.toUpperCase());
        int systemOrderOffset = systemMaxOrder == null ? 0 : systemMaxOrder;
        for (int index = 0; index < optionIds.size(); index++) {
            UUID id = optionIds.get(index);
            int sortOrder = systemOrderOffset + (index + 1) * 10;
            Integer previousOrder = jdbc.queryForObject(
                    "SELECT sort_order FROM tenant_config_options WHERE id = ? AND tenant_id = ?",
                    Integer.class, id, context.tenantId());
            jdbc.update("UPDATE tenant_config_options SET sort_order = ?, updated_at = NOW() "
                    + "WHERE id = ? AND tenant_id = ?", sortOrder, id, context.tenantId());
            if (previousOrder != null && previousOrder != sortOrder) {
                audit.record("CONFIG_OPTION", id, "REORDERED",
                        "{\"sortOrder\":" + previousOrder + "}", "{\"sortOrder\":" + sortOrder + "}");
                jdbc.update("""
                        INSERT INTO tenant_config_option_history
                          (option_id, tenant_id, changed_by, change_type, old_sort_order, new_sort_order)
                        VALUES (?, ?, ?, 'REORDERED', ?, ?)
                        """, id, context.tenantId(), context.userId(), previousOrder, sortOrder);
            }
        }
    }

    private void requireTenantEditable(String categoryCode) {
        List<String> scopes = jdbc.query("SELECT scope FROM config_categories WHERE code = ? AND is_active",
                (rs, row) -> rs.getString(1), categoryCode == null ? null : categoryCode.toUpperCase());
        if (scopes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown config category");
        }
        if ("system".equals(scopes.get(0))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "System-controlled categories cannot be changed by tenants");
        }
    }

    private static void requireOwner(BossContext context) {
        if (!context.hasRole("OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners may edit configuration options");
        }
    }

    private static BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null) throw new IllegalStateException("BossContext required");
        return context;
    }
}
