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

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ConfigOptionCreateService {

    private static final Set<String> DISPATCH_OPTION_CATEGORIES = Set.of("CARRIER", "VEHICLE");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ConfigOptionQueryService queryService;
    private final ObjectMapper objectMapper;

    public ConfigOptionCreateService(JdbcTemplate jdbc, AuditService audit,
                                     ConfigOptionQueryService queryService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("hasAnyRole('OWNER','OPS_MANAGER','STORE_KEEPER')")
    @Transactional
    public ConfigOptionDto createOption(String categoryCode, String code, String label, int sortOrder) {
        BossContext context = requireContext();
        requireOptionCreatePermission(context, categoryCode);
        requireTenantEditable(categoryCode);
        if (code == null || code.isBlank() || label == null || label.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code and label are required");
        }
        String normalizedCode = code.trim();
        String normalizedLabel = label.trim();
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM tenant_config_options o JOIN config_categories c ON c.id = o.category_id
                WHERE o.tenant_id = ? AND c.code = ? AND (lower(o.code) = lower(?) OR lower(o.label) = lower(?)))
                """, Boolean.class, context.tenantId(), categoryCode.toUpperCase(), normalizedCode, normalizedLabel));
        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM item_categories
                    WHERE tenant_id = ? AND (lower(code) = lower(?) OR lower(name) = lower(?)))
                    """, Boolean.class, context.tenantId(), normalizedCode, normalizedLabel));
        }
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An option with this code or label already exists");
        }

        if ("ITEM_CATEGORY".equalsIgnoreCase(categoryCode)) {
            UUID id = UUID.fromString(Objects.requireNonNull(jdbc.queryForObject(
                    "INSERT INTO item_categories (tenant_id, code, name, is_system, is_active) "
                    + "VALUES (?, ?, ?, false, true) RETURNING id::text",
                    String.class,
                    context.tenantId(), normalizedCode, normalizedLabel)));
            ConfigOptionDto created = new ConfigOptionDto(id, "ITEM_CATEGORY", normalizedCode, normalizedLabel,
                    sortOrder, false, true);
            audit.record("ITEM_CATEGORY", id, "CREATE", null,
                    ConfigOptionAuditSerializer.toJson(objectMapper, created));
            return created;
        }

        UUID categoryId = queryService.resolveCategoryId(categoryCode);
        UUID id = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_config_options "
                + "(tenant_id, category_id, code, label, sort_order) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id::text",
                String.class,
                context.tenantId(), categoryId, normalizedCode, normalizedLabel, sortOrder));
        ConfigOptionDto created = new ConfigOptionDto(id, categoryCode, normalizedCode, normalizedLabel,
                sortOrder, false, true);
        audit.record("CONFIG_OPTION", id, "CREATE", null,
                ConfigOptionAuditSerializer.toJson(objectMapper, created));
        ConfigOptionHistoryService.record(jdbc, id, context, "create", null, normalizedLabel);
        return created;
    }

    private void requireTenantEditable(String categoryCode) {
        var scopes = jdbc.query("SELECT scope FROM config_categories WHERE code = ? AND is_active",
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
        if (context.hasRole("OWNER")) return;
        String normalizedCategory = categoryCode == null ? "" : categoryCode.toUpperCase(java.util.Locale.ROOT);
        if (context.hasAnyRole("OPS_MANAGER", "STORE_KEEPER")
                && DISPATCH_OPTION_CATEGORIES.contains(normalizedCategory)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only owners may create options outside dispatch categories");
    }

    private static BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null) throw new IllegalStateException("BossContext required");
        return context;
    }
}
