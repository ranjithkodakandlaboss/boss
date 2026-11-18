package in.boss.shared.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConfigOptionQueryService {

    private final JdbcTemplate jdbc;

    public ConfigOptionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    public List<ConfigOptionDto> getOptions(UUID tenantId, String categoryCode, boolean includeInactive) {
        return loadOptions(tenantId, categoryCode, includeInactive);
    }

    Optional<ConfigOptionDto> findOption(UUID tenantId, String categoryCode, UUID optionId) {
        return loadOptions(tenantId, categoryCode, true).stream()
                .filter(option -> option.id().equals(optionId)).findFirst();
    }

    UUID resolveCategoryId(String categoryCode) {
        return UUID.fromString(Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id::text FROM config_categories WHERE code = ?",
                        String.class, categoryCode),
                "Config category not found: " + categoryCode));
    }

    private List<ConfigOptionDto> loadOptions(UUID tenantId, String categoryCode, boolean includeInactive) {
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            return jdbc.query(
                    "SELECT id, code, name AS label, 0 AS sort_order, is_system, is_active, description "
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
                            rs.getBoolean("is_active"),
                            rs.getString("description") == null ? "" : rs.getString("description")),
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
}
