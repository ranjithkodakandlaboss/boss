package in.boss.admin.domain;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Calls the shared {@code seed_tenant_defaults} function inside the current
 * provisioning transaction and checks that its result is actually present.
 */
@Component
public class TenantDefaultsSeed {
    private static final Logger log = LoggerFactory.getLogger(TenantDefaultsSeed.class);
    static final int STANDARD_UOM_COUNT = 16;

    private final JdbcTemplate jdbc;

    public TenantDefaultsSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void apply(UUID tenantId, String industryType) {
        Integer inserted = jdbc.queryForObject(
                "SELECT seed_tenant_defaults(?, ?)", Integer.class, tenantId, industryType);
        if (inserted == null || !isComplete(tenantId, industryType)) {
            throw new IllegalStateException("Industry seeding did not complete for " + industryType);
        }
        log.info("Industry seeding completed tenantId={} industryType={} inserted={}",
                tenantId, industryType, inserted);
    }

    public boolean isComplete(UUID tenantId, String industryType) {
        Integer templateCount = jdbc.queryForObject(
                "SELECT count(*) FROM industry_template_options WHERE industry_type = ?",
                Integer.class, industryType);
        Integer itemTemplates = jdbc.queryForObject(
                "SELECT count(*) FROM industry_template_options " +
                        "WHERE industry_type = ? AND category_code = 'ITEM_CATEGORY'",
                Integer.class, industryType);
        Integer otherTemplates = jdbc.queryForObject(
                "SELECT count(*) FROM industry_template_options " +
                        "WHERE industry_type = ? AND category_code <> 'ITEM_CATEGORY'",
                Integer.class, industryType);
        Integer items = jdbc.queryForObject(
                "SELECT count(*) FROM item_categories ic WHERE ic.tenant_id = ? AND EXISTS (" +
                        "SELECT 1 FROM industry_template_options t " +
                        "WHERE t.industry_type = ? AND t.category_code = 'ITEM_CATEGORY' AND t.code = ic.code)",
                Integer.class, tenantId, industryType);
        Integer options = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_config_options o " +
                        "JOIN config_categories c ON c.id = o.category_id " +
                        "JOIN industry_template_options t ON t.industry_type = ? " +
                        "AND t.category_code = c.code AND t.category_code <> 'ITEM_CATEGORY' AND t.code = o.code " +
                        "WHERE o.tenant_id = ?",
                Integer.class, industryType, tenantId);
        Integer units = jdbc.queryForObject(
                "SELECT count(*) FROM units_of_measure WHERE tenant_id = ?",
                Integer.class, tenantId);
        Integer onboarding = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_onboarding_config WHERE tenant_id = ? AND industry_type = ?",
                Integer.class, tenantId, industryType);
        return templateCount != null && templateCount > 0
                && itemTemplates != null && items != null && items >= itemTemplates
                && otherTemplates != null && options != null && options >= otherTemplates
                && units != null && units >= STANDARD_UOM_COUNT
                && onboarding != null && onboarding == 1;
    }
}
