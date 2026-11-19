package in.boss.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConfigOptionLifecycleService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ConfigOptionQueryService queryService;
    private final ObjectMapper objectMapper;

    public ConfigOptionLifecycleService(JdbcTemplate jdbc, AuditService audit,
                                        ConfigOptionQueryService queryService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public ConfigOptionDto updateOption(String categoryCode, UUID optionId, String label) {
        BossContext context = requireContext();
        requireOwner(context);
        requireTenantEditable(categoryCode);
        lockOption(context.tenantId(), categoryCode, optionId);
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Item-category labels cannot be changed");
        }
        if (label == null || label.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label is required");
        }
        ConfigOptionDto existing = queryService.findOption(context.tenantId(), categoryCode, optionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Configuration option not found"));
        if (existing.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "System-controlled options cannot be changed");
        }
        List<String> dependencies = findReferences("tenant_config_options", optionId, context.tenantId());
        if (!dependencies.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This option is referenced by business records and cannot be changed: "
                            + String.join(", ", dependencies));
        }
        String normalizedLabel = label.trim();
        boolean duplicateLabel = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND o.id <> ? AND lower(o.label) = lower(?))
                """, Boolean.class, context.tenantId(), categoryCode.toUpperCase(), optionId, normalizedLabel));
        if (duplicateLabel) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An option with this label already exists");
        }
        jdbc.update("UPDATE tenant_config_options SET label = ?, updated_at = NOW() "
                        + "WHERE id = ? AND tenant_id = ? AND is_system = FALSE",
                normalizedLabel, optionId, context.tenantId());
        ConfigOptionDto updated = new ConfigOptionDto(existing.id(), existing.categoryCode(), existing.code(),
                normalizedLabel, existing.sortOrder(), false, existing.isActive());
        audit.record("CONFIG_OPTION", optionId, "UPDATE",
                ConfigOptionAuditSerializer.toJson(objectMapper, existing),
                ConfigOptionAuditSerializer.toJson(objectMapper, updated));
        ConfigOptionHistoryService.record(jdbc, optionId, context, "update",
                existing.label(), normalizedLabel);
        return updated;
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public void setOptionActive(String categoryCode, UUID optionId, boolean active) {
        BossContext context = requireContext();
        requireOwner(context);
        requireTenantEditable(categoryCode);
        lockOption(context.tenantId(), categoryCode, optionId);
        ConfigOptionDto existing = queryService.findOption(context.tenantId(), categoryCode, optionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Configuration option not found"));
        if (!active && existing.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "System-controlled options cannot be deactivated");
        }
        if (existing.isActive() == active) return;
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            if (!active) {
                List<String> dependencies = findActiveCategoryDependencies(context.tenantId(), optionId);
                if (!dependencies.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cannot deactivate category used by " + String.join(", ", dependencies));
                }
            } else {
                UUID parentId = jdbc.queryForObject(
                        "SELECT parent_id FROM item_categories WHERE id = ? AND tenant_id = ?",
                        UUID.class, optionId, context.tenantId());
                if (parentId != null) {
                    List<Boolean> parents = jdbc.query("""
                            SELECT is_active FROM item_categories
                            WHERE id = ? AND tenant_id = ? FOR UPDATE
                            """, (rs, row) -> rs.getBoolean(1), parentId, context.tenantId());
                    if (parents.isEmpty() || !parents.get(0)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Cannot reactivate a category with an inactive or unavailable parent");
                    }
                }
            }
            jdbc.update("UPDATE item_categories SET is_active = ? WHERE id = ? AND tenant_id = ?",
                    active, optionId, context.tenantId());
        } else {
            jdbc.update("UPDATE tenant_config_options SET is_active = ?, updated_at = NOW() "
                            + "WHERE id = ? AND tenant_id = ?",
                    active, optionId, context.tenantId());
        }
        audit.record("CONFIG_OPTION", optionId, active ? "ACTIVATE" : "DEACTIVATE",
                ConfigOptionAuditSerializer.toJson(objectMapper, existing), "{\"active\":" + active + "}");
        if (!"ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            ConfigOptionHistoryService.record(jdbc, optionId, context, active ? "reactivate" : "deactivate",
                    existing.label(), existing.label());
        }
    }

    private void lockOption(UUID tenantId, String categoryCode, UUID optionId) {
        String table = "ITEM_CATEGORY".equalsIgnoreCase(categoryCode) ? "item_categories" : "tenant_config_options";
        jdbc.queryForList("SELECT id FROM " + table + " WHERE tenant_id = ? AND id = ? FOR UPDATE",
                UUID.class, tenantId, optionId);
    }

    private List<String> findActiveCategoryDependencies(UUID tenantId, UUID categoryId) {
        Integer items = jdbc.queryForObject("""
                SELECT COUNT(*) FROM items WHERE tenant_id = ? AND category_id = ? AND is_active
                """, Integer.class, tenantId, categoryId);
        Integer children = jdbc.queryForObject("""
                SELECT COUNT(*) FROM item_categories WHERE tenant_id = ? AND parent_id = ? AND is_active
                """, Integer.class, tenantId, categoryId);
        List<String> dependencies = new ArrayList<>();
        if (items != null && items > 0) dependencies.add(items + (items == 1 ? " active item" : " active items"));
        if (children != null && children > 0) {
            dependencies.add(children + (children == 1 ? " active child category" : " active child categories"));
        }
        return dependencies;
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

    private List<String> findReferences(String targetTable, UUID optionId, UUID tenantId) {
        List<ReferenceColumn> references = jdbc.query("""
                SELECT source_ns.nspname AS schema_name, source_table.relname AS table_name,
                       source_column.attname AS column_name,
                       EXISTS (SELECT 1 FROM pg_attribute tenant_column
                               WHERE tenant_column.attrelid = source_table.oid
                                 AND tenant_column.attname = 'tenant_id'
                                 AND tenant_column.attnum > 0 AND NOT tenant_column.attisdropped) AS tenant_scoped
                FROM pg_constraint fk
                JOIN pg_class source_table ON source_table.oid = fk.conrelid
                JOIN pg_namespace source_ns ON source_ns.oid = source_table.relnamespace
                JOIN pg_attribute source_column ON source_column.attrelid = fk.conrelid
                                               AND source_column.attnum = fk.conkey[1]
                WHERE fk.contype = 'f' AND fk.confrelid = ?::regclass
                  AND array_length(fk.conkey, 1) = 1
                  AND source_ns.nspname = 'public'
                  AND source_table.relname <> 'tenant_config_option_history'
                """, (rs, row) -> new ReferenceColumn(rs.getString("schema_name"), rs.getString("table_name"),
                rs.getString("column_name"), rs.getBoolean("tenant_scoped")), targetTable);
        List<String> dependencies = new ArrayList<>();
        for (ReferenceColumn reference : references) {
            String table = quoteIdentifier(reference.schemaName()) + "." + quoteIdentifier(reference.tableName());
            String column = quoteIdentifier(reference.columnName());
            String tenantPredicate = reference.tenantScoped() ? " AND tenant_id = ?" : "";
            String query = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?" + tenantPredicate;
            Long count = reference.tenantScoped()
                    ? jdbc.queryForObject(query, Long.class, optionId, tenantId)
                    : jdbc.queryForObject(query, Long.class, optionId);
            if (count != null && count > 0) {
                dependencies.add(reference.tableName().replace('_', ' ') + ": " + count);
            }
        }
        return dependencies;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
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

    private record ReferenceColumn(String schemaName, String tableName,
                                   String columnName, boolean tenantScoped) {}
}
