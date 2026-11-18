package in.boss.shared.config;

import in.boss.shared.context.BossContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config options service (issue #90).
 *
 * Reads from tenant_config_options joined to config_categories.
 * Returns active options for the current tenant, ordered by sort_order.
 * Write operations are OWNER-only (see BossPermissions.CONFIG_WRITE).
 */
@Service
public class ConfigService {

    private final JdbcTemplate jdbc;

    // Simple in-process cache: tenantId -> categoryCode -> options list
    // Evicted on any write to that tenant's options.
    private final Map<UUID, Map<String, List<ConfigOptionDto>>> cache =
            new ConcurrentHashMap<>();

    public ConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns active options for {@code categoryCode} for the current tenant.
     * Results are cached until any write occurs for that tenant.
     */
    public List<ConfigOptionDto> getOptions(String categoryCode) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");

        return cache
                .computeIfAbsent(ctx.tenantId(), t -> new ConcurrentHashMap<>())
                .computeIfAbsent(categoryCode, c -> loadOptions(ctx.tenantId(), c));
    }

    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public ConfigOptionDto createOption(String categoryCode, String code,
                                        String label, int sortOrder) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");

        List<String> scopes = jdbc.query("SELECT scope FROM config_categories WHERE code = ? AND is_active",
                (rs, i) -> rs.getString(1), categoryCode == null ? null : categoryCode.toUpperCase());
        if (scopes.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown config category");
        }
        if ("system".equals(scopes.get(0))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "System-controlled categories cannot be changed by tenants");
        }
        if (code == null || code.isBlank() || label == null || label.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Code and label are required");
        }
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM tenant_config_options o JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND (lower(o.code) = lower(?) OR lower(o.label) = lower(?)))
                """, Boolean.class, ctx.tenantId(), categoryCode.toUpperCase(), code.trim(), label.trim()));
        if (exists) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "An option with this code or label already exists");
        }

        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            UUID id = UUID.fromString(Objects.requireNonNull(jdbc.queryForObject(
                    "INSERT INTO item_categories (tenant_id, code, name, is_system, is_active) "
                    + "VALUES (?, ?, ?, false, true) RETURNING id::text",
                    String.class,
                    ctx.tenantId(), code, label)));
            evict(ctx.tenantId(), "ITEM_CATEGORY");
            return new ConfigOptionDto(id, "ITEM_CATEGORY", code, label, sortOrder, false);
        }

        UUID categoryId = resolveCategoryId(categoryCode);

        UUID id = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_config_options "
                + "(tenant_id, category_id, code, label, sort_order) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id::text",
                String.class,
                ctx.tenantId(), categoryId, code, label, sortOrder));

        evict(ctx.tenantId(), categoryCode);
        return new ConfigOptionDto(id, categoryCode, code, label, sortOrder, false);
    }

    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public void deactivateOption(UUID optionId) {
        var ctx = BossContextHolder.get();
        if (ctx == null) throw new IllegalStateException("BossContext required");

        int updated = jdbc.update(
                "UPDATE tenant_config_options SET is_active = false "
                + "WHERE id = ? AND tenant_id = ?",
                optionId, ctx.tenantId());
        if (updated == 0) {
            jdbc.update(
                    "UPDATE item_categories SET is_active = false "
                    + "WHERE id = ? AND tenant_id = ?",
                    optionId, ctx.tenantId());
        }

        cache.remove(ctx.tenantId());  // evict all categories for this tenant
    }

    private List<ConfigOptionDto> loadOptions(UUID tenantId, String categoryCode) {
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            return jdbc.query(
                    "SELECT id, code, name AS label, 0 AS sort_order, is_system "
                    + "FROM item_categories "
                    + "WHERE tenant_id = ? AND is_active = true "
                    + "ORDER BY name",
                    (rs, row) -> new ConfigOptionDto(
                            UUID.fromString(rs.getString("id")),
                            "ITEM_CATEGORY",
                            rs.getString("code"),
                            rs.getString("label"),
                            rs.getInt("sort_order"),
                            rs.getBoolean("is_system")),
                    tenantId);
        }

        return jdbc.query(
                "SELECT o.id, cc.code AS cat_code, o.code, o.label, o.sort_order, o.is_system "
                + "FROM tenant_config_options o "
                + "JOIN config_categories cc ON cc.id = o.category_id "
                + "WHERE o.tenant_id = ? AND cc.code = ? AND o.is_active = true "
                + "ORDER BY o.sort_order, o.label",
                (rs, row) -> new ConfigOptionDto(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("cat_code"),
                        rs.getString("code"),
                        rs.getString("label"),
                        rs.getInt("sort_order"),
                        rs.getBoolean("is_system")),
                tenantId, categoryCode);
    }

    private UUID resolveCategoryId(String categoryCode) {
        return UUID.fromString(Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id::text FROM config_categories WHERE code = ?",
                        String.class, categoryCode),
                "Config category not found: " + categoryCode));
    }

    private void evict(UUID tenantId, String categoryCode) {
        var byCategory = cache.get(tenantId);
        if (byCategory != null) byCategory.remove(categoryCode);
    }
}
