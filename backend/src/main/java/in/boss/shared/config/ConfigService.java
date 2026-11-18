package in.boss.shared.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Config options service (issue #90).
 *
 * Reads from tenant_config_options joined to config_categories.
 * Returns active options for the current tenant, ordered by sort_order.
 * General configuration writes are OWNER-only; dispatch options have limited write access.
 */
@Service
public class ConfigService {

    private static final Set<String> DISPATCH_OPTION_CATEGORIES = Set.of("CARRIER", "VEHICLE");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ConfigService(JdbcTemplate jdbc, AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<ConfigCategoryDto> getCategories() {
        return jdbc.query("""
                SELECT code, name, scope
                FROM config_categories
                WHERE is_active = TRUE
                ORDER BY name
                """, (rs, row) -> {
            String code = rs.getString("code");
            return new ConfigCategoryDto(code, rs.getString("name"), rs.getString("scope"),
                    !"system".equals(rs.getString("scope")), !"ITEM_CATEGORY".equals(code));
        });
    }

    public List<ConfigOptionDto> getOptions(String categoryCode, boolean includeInactive) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");
        return loadOptions(ctx.tenantId(), categoryCode, includeInactive);
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public ConfigOptionDto createOption(String categoryCode, String code,
                                        String label, int sortOrder) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");

        requireOptionCreatePermission(ctx, categoryCode);
        requireTenantEditable(categoryCode);
        if (code == null || code.isBlank() || label == null || label.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code and label are required");
        }
        String normalizedCode = code.trim();
        String normalizedLabel = label.trim();
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM tenant_config_options o JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND (lower(o.code) = lower(?) OR lower(o.label) = lower(?)))
                """, Boolean.class, ctx.tenantId(), categoryCode.toUpperCase(), normalizedCode, normalizedLabel));
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM item_categories
                    WHERE tenant_id = ? AND (lower(code) = lower(?) OR lower(name) = lower(?)))
                    """, Boolean.class, ctx.tenantId(), normalizedCode, normalizedLabel));
        }
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An option with this code or label already exists");
        }

        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            UUID id = UUID.fromString(Objects.requireNonNull(jdbc.queryForObject(
                    "INSERT INTO item_categories (tenant_id, code, name, is_system, is_active) "
                    + "VALUES (?, ?, ?, false, true) RETURNING id::text",
                    String.class,
                    ctx.tenantId(), normalizedCode, normalizedLabel)));
            ConfigOptionDto created = new ConfigOptionDto(id, "ITEM_CATEGORY", normalizedCode, normalizedLabel, sortOrder, false, true);
            audit.record("ITEM_CATEGORY", id, "CREATE", null, optionJson(created));
            return created;
        }

        UUID categoryId = resolveCategoryId(categoryCode);

        UUID id = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_config_options "
                + "(tenant_id, category_id, code, label, sort_order) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id::text",
                String.class,
                ctx.tenantId(), categoryId, normalizedCode, normalizedLabel, sortOrder));

        ConfigOptionDto created = new ConfigOptionDto(id, categoryCode, normalizedCode, normalizedLabel, sortOrder, false, true);
        audit.record("CONFIG_OPTION", id, "CREATE", null, optionJson(created));
        recordOptionHistory(id, ctx, "create", null, normalizedLabel);
        return created;
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public ConfigOptionDto updateOption(String categoryCode, UUID optionId, String label) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");
        requireOwner(ctx);
        requireTenantEditable(categoryCode);
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Item-category labels cannot be changed");
        }
        if (label == null || label.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label is required");
        }
        ConfigOptionDto existing = findOption(ctx.tenantId(), categoryCode, optionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration option not found"));
        if (existing.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System-controlled options cannot be changed");
        }
        List<String> dependencies = findReferences("tenant_config_options", optionId, ctx.tenantId());
        if (!dependencies.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This option is referenced by business records and cannot be changed: " + String.join(", ", dependencies));
        }
        String normalizedLabel = label.trim();
        boolean duplicateLabel = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND o.id <> ? AND lower(o.label) = lower(?))
                """, Boolean.class, ctx.tenantId(), categoryCode.toUpperCase(), optionId, normalizedLabel));
        if (duplicateLabel) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An option with this label already exists");
        }
        jdbc.update("UPDATE tenant_config_options SET label = ?, updated_at = NOW() "
                        + "WHERE id = ? AND tenant_id = ? AND is_system = FALSE",
                normalizedLabel, optionId, ctx.tenantId());
        ConfigOptionDto updated = new ConfigOptionDto(existing.id(), existing.categoryCode(), existing.code(),
                normalizedLabel, existing.sortOrder(), false, existing.isActive());
        audit.record("CONFIG_OPTION", optionId, "UPDATE", optionJson(existing), optionJson(updated));
        recordOptionHistory(optionId, ctx, "update", existing.label(), normalizedLabel);
        return updated;
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public void setOptionActive(String categoryCode, UUID optionId, boolean active) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");
        requireOwner(ctx);
        requireTenantEditable(categoryCode);
        ConfigOptionDto existing = findOption(ctx.tenantId(), categoryCode, optionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration option not found"));
        if (existing.isActive() == active) {
            return;
        }
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            jdbc.update("UPDATE item_categories SET is_active = ? WHERE id = ? AND tenant_id = ?",
                    active, optionId, ctx.tenantId());
        } else {
            jdbc.update("UPDATE tenant_config_options SET is_active = ?, updated_at = NOW() "
                            + "WHERE id = ? AND tenant_id = ?",
                    active, optionId, ctx.tenantId());
        }
        audit.record("CONFIG_OPTION", optionId, active ? "ACTIVATE" : "DEACTIVATE",
                optionJson(existing), "{\"active\":" + active + "}");
        if (!"ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            recordOptionHistory(optionId, ctx, active ? "reactivate" : "deactivate",
                            existing.label(), existing.label());
        }
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public void reorderOptions(String categoryCode, List<UUID> optionIds) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");
        requireOwner(ctx);
        requireTenantEditable(categoryCode);
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item categories do not support custom ordering");
        }
        List<UUID> currentIds = jdbc.query("""
                SELECT o.id FROM tenant_config_options o
                JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND o.is_active = TRUE AND o.is_system = FALSE
                ORDER BY o.sort_order, o.label
                """, (rs, row) -> rs.getObject("id", UUID.class), ctx.tenantId(), categoryCode.toUpperCase());
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
                """, Integer.class, ctx.tenantId(), categoryCode.toUpperCase());
        int systemOrderOffset = systemMaxOrder == null ? 0 : systemMaxOrder;
        for (int index = 0; index < optionIds.size(); index++) {
            UUID id = optionIds.get(index);
            int sortOrder = systemOrderOffset + (index + 1) * 10;
            Integer previousOrder = jdbc.queryForObject(
                    "SELECT sort_order FROM tenant_config_options WHERE id = ? AND tenant_id = ?",
                    Integer.class, id, ctx.tenantId());
            jdbc.update("UPDATE tenant_config_options SET sort_order = ?, updated_at = NOW() "
                    + "WHERE id = ? AND tenant_id = ?", sortOrder, id, ctx.tenantId());
            if (previousOrder != null && previousOrder != sortOrder) {
                audit.record("CONFIG_OPTION", id, "REORDERED",
                        "{\"sortOrder\":" + previousOrder + "}", "{\"sortOrder\":" + sortOrder + "}");
                jdbc.update("""
                        INSERT INTO tenant_config_option_history
                          (option_id, tenant_id, changed_by, change_type, old_sort_order, new_sort_order)
                        VALUES (?, ?, ?, 'REORDERED', ?, ?)
                        """, id, ctx.tenantId(), ctx.userId(), previousOrder, sortOrder);
            }
        }
    }

    private List<ConfigOptionDto> loadOptions(UUID tenantId, String categoryCode, boolean includeInactive) {
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            return jdbc.query(
                    "SELECT id, code, name AS label, 0 AS sort_order, is_system, is_active "
                    + "FROM item_categories "
                    + "WHERE tenant_id = ? " + (includeInactive ? "" : "AND is_active = true ")
                    + "ORDER BY name",
                    (rs, row) -> new ConfigOptionDto(
                            UUID.fromString(rs.getString("id")),
                            "ITEM_CATEGORY",
                            rs.getString("code"),
                            rs.getString("label"),
                            rs.getInt("sort_order"),
                            rs.getBoolean("is_system"),
                            rs.getBoolean("is_active")),
                    tenantId);
        }

        return jdbc.query(
                "SELECT o.id, cc.code AS cat_code, o.code, o.label, o.sort_order, o.is_system, o.is_active "
                + "FROM tenant_config_options o "
                + "JOIN config_categories cc ON cc.id = o.category_id "
                + "WHERE o.tenant_id = ? AND cc.code = ? " + (includeInactive ? "" : "AND o.is_active = true ")
                + "ORDER BY o.sort_order, o.label",
                (rs, row) -> new ConfigOptionDto(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("cat_code"),
                        rs.getString("code"),
                        rs.getString("label"),
                        rs.getInt("sort_order"),
                        rs.getBoolean("is_system"),
                        rs.getBoolean("is_active")),
                tenantId, categoryCode);
    }

    private Optional<ConfigOptionDto> findOption(UUID tenantId, String categoryCode, UUID optionId) {
        return loadOptions(tenantId, categoryCode, true).stream()
                .filter(option -> option.id().equals(optionId)).findFirst();
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

    private static void requireOptionCreatePermission(BossContext context, String categoryCode) {
        if (context.hasRole("OWNER")) {
            return;
        }
        String normalizedCategory = categoryCode == null ? "" : categoryCode.toUpperCase(Locale.ROOT);
        if (context.hasAnyRole("OPS_MANAGER", "STORE_KEEPER")
                && DISPATCH_OPTION_CATEGORIES.contains(normalizedCategory)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only owners may create options outside dispatch categories");
    }

    private static void requireOwner(BossContext context) {
        if (!context.hasRole("OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only owners may edit configuration options");
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

    private record ReferenceColumn(String schemaName, String tableName, String columnName, boolean tenantScoped) {}

    private String optionJson(ConfigOptionDto option) {
        try {
            return objectMapper.writeValueAsString(option);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize configuration audit data", e);
        }
    }

    private UUID resolveCategoryId(String categoryCode) {
        return UUID.fromString(Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id::text FROM config_categories WHERE code = ?",
                        String.class, categoryCode),
                "Config category not found: " + categoryCode));
    }

    private void recordOptionHistory(UUID optionId, BossContext context, String changeType,
                                     String oldLabel, String newLabel) {
        jdbc.update("""
                INSERT INTO tenant_config_option_history
                  (option_id, tenant_id, changed_by, old_label, new_label, change_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """, optionId, context.tenantId(), context.userId(), oldLabel, newLabel, changeType);
    }

    public List<ConfigOptionDto> getOptions(String categoryCode) {
        return getOptions(categoryCode, false);
    }
}
