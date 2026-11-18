package in.boss.masterdata.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ItemOperationalDependencies {
    private final NamedParameterJdbcTemplate jdbc;

    public ItemOperationalDependencies(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findActive(UUID tenantId, UUID itemId) {
        return jdbc.queryForList("""
                SELECT 'customer purchase order' AS dependency WHERE EXISTS (
                    SELECT 1 FROM cpo_lines l JOIN cpos h ON h.id = l.cpo_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('fulfilled', 'cancelled'))
                UNION ALL
                SELECT 'purchase requisition' WHERE EXISTS (
                    SELECT 1 FROM pr_lines l JOIN purchase_requisitions h ON h.id = l.pr_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('fulfilled', 'rejected', 'cancelled'))
                UNION ALL
                SELECT 'supplier purchase order' WHERE EXISTS (
                    SELECT 1 FROM spo_lines l JOIN spos h ON h.id = l.spo_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('fully_received', 'declined', 'cancelled'))
                UNION ALL
                SELECT 'production job' WHERE EXISTS (
                    SELECT 1 FROM production_jobs j
                    WHERE j.tenant_id = :tenant AND j.status NOT IN ('completed', 'cancelled')
                      AND (j.finished_item_id = :item OR EXISTS (
                          SELECT 1 FROM job_material_requirements r
                          WHERE r.tenant_id = j.tenant_id AND r.job_id = j.id AND r.item_id = :item)))
                UNION ALL
                SELECT 'stock on hand' WHERE EXISTS (
                    SELECT 1 FROM material_lots l WHERE l.tenant_id = :tenant AND l.item_id = :item AND l.quantity > 0)
                UNION ALL
                SELECT 'stock reservation' WHERE EXISTS (
                    SELECT 1 FROM stock_reservation_records r
                    WHERE r.tenant_id = :tenant AND r.item_id = :item AND r.status = 'active')
                UNION ALL
                SELECT 'dispatch' WHERE EXISTS (
                    SELECT 1 FROM dispatch_lines l JOIN dispatches h ON h.id = l.dispatch_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('delivered', 'cancelled'))
                """, new MapSqlParameterSource().addValue("tenant", tenantId).addValue("item", itemId), String.class);
    }
}
