package in.boss.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Industry template service (issue #91).
 *
 * Applies one of the six approved industry option sets to a tenant's
 * tenant_config_options table.  Called once at tenant onboarding, but
 * idempotent — safe to call multiple times on the same tenant.
 *
 * Approved industry types (defined in V062 industry_template_options):
 *   FABRICATION_STEEL, PRECISION_MACHINING, ELECTRONICS_ASSEMBLY,
 *   FORGING, CASTING, GENERAL_MANUFACTURING
 */
@Service
public class IndustryTemplateService {

    private static final Logger log = LoggerFactory.getLogger(IndustryTemplateService.class);

    public static final List<String> SUPPORTED_INDUSTRY_TYPES = List.of(
            "FABRICATION_STEEL",
            "PRECISION_MACHINING",
            "ELECTRONICS_ASSEMBLY",
            "FORGING",
            "CASTING",
            "GENERAL_MANUFACTURING");

    private final JdbcTemplate jdbc;

    public IndustryTemplateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Seeds config options for {@code industryType} into the target tenant.
     * Idempotent — skips rows that already exist (ON CONFLICT DO NOTHING).
     * Preserves any tenant-customized values (they have distinct codes).
     *
     * @param tenantId     target tenant (must already exist in tenant_registry)
     * @param industryType one of SUPPORTED_INDUSTRY_TYPES
     */
    @Transactional
    public void applyTemplate(UUID tenantId, String industryType) {
        if (!SUPPORTED_INDUSTRY_TYPES.contains(industryType)) {
            throw new IllegalArgumentException(
                    "Unknown industry type: " + industryType
                    + ". Supported: " + SUPPORTED_INDUSTRY_TYPES);
        }

        List<Map<String, Object>> templateRows = jdbc.queryForList(
                "SELECT t.category_code, t.code, t.label, t.sort_order, "
                + "       c.id AS category_id "
                + "FROM industry_template_options t "
                + "JOIN config_categories c ON c.code = t.category_code "
                + "WHERE t.industry_type = ?",
                industryType);

        int inserted = 0;
        for (var row : templateRows) {
            int rows = jdbc.update(
                    "INSERT INTO tenant_config_options "
                    + "(tenant_id, category_id, code, label, sort_order, is_system) "
                    + "VALUES (?, ?, ?, ?, ?, true) "
                    + "ON CONFLICT (tenant_id, category_id, code) DO NOTHING",
                    tenantId,
                    row.get("category_id"),
                    row.get("code"),
                    row.get("label"),
                    row.get("sort_order"));
            inserted += rows;
        }

        log.info("Applied industry template {} to tenant {}: {} options inserted",
                industryType, tenantId, inserted);
    }

    /**
     * Seeds standard UOMs for the tenant.
     * Common across all industries; called after applyTemplate.
     * Idempotent — ON CONFLICT DO NOTHING.
     */
    @Transactional
    public void seedStandardUoms(UUID tenantId) {
        var uomData = List.of(
            new Object[]{"NOS", "Numbers",       "quantity", true},
            new Object[]{"PCS", "Pieces",        "quantity", false},
            new Object[]{"KG",  "Kilogram",      "weight",   true},
            new Object[]{"G",   "Gram",          "weight",   false},
            new Object[]{"MT",  "Metric Tonne",  "weight",   false},
            new Object[]{"M",   "Metre",         "length",   true},
            new Object[]{"MM",  "Millimetre",    "length",   false},
            new Object[]{"CM",  "Centimetre",    "length",   false},
            new Object[]{"L",   "Litre",         "volume",   true},
            new Object[]{"ML",  "Millilitre",    "volume",   false},
            new Object[]{"SQM", "Square Metre",  "area",     true},
            new Object[]{"HR",  "Hour",          "time",     true},
            new Object[]{"MIN", "Minute",        "time",     false},
            new Object[]{"SET", "Set",           "quantity", false},
            new Object[]{"BOX", "Box",           "quantity", false},
            new Object[]{"CTN", "Carton",        "quantity", false}
        );

        int inserted = 0;
        for (var uom : uomData) {
            int rows = jdbc.update(
                    "INSERT INTO units_of_measure (tenant_id, name, symbol, uom_type, is_base) "
                    + "VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT (tenant_id, symbol) DO NOTHING",
                    tenantId, uom[1], uom[0], uom[2], uom[3]);
            inserted += rows;
        }
        log.info("Seeded {} standard UOMs for tenant {}", inserted, tenantId);
    }
}
