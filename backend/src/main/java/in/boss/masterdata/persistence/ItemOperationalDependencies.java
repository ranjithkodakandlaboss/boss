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
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('ordered', 'fulfilled', 'rejected', 'cancelled'))
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
                          WHERE r.tenant_id = j.tenant_id AND r.job_id = j.id AND r.item_id = :item)
                          OR EXISTS (SELECT 1 FROM job_material_issues r
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
                UNION ALL
                SELECT 'sales RFQ' WHERE EXISTS (
                    SELECT 1 FROM rfq_lines l JOIN rfqs h ON h.id = l.rfq_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('closed', 'cancelled'))
                UNION ALL
                SELECT 'sales quotation' WHERE EXISTS (
                    SELECT 1 FROM quotation_lines l JOIN quotations h ON h.id = l.quotation_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('accepted', 'rejected', 'expired', 'superseded'))
                UNION ALL
                SELECT 'supplier quotation' WHERE EXISTS (
                    SELECT 1 FROM supplier_quotation_lines l JOIN supplier_quotations h ON h.id = l.sq_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('selected', 'rejected'))
                UNION ALL
                SELECT 'frame agreement' WHERE EXISTS (
                    SELECT 1 FROM frame_agreement_lines l JOIN frame_agreements h ON h.id = l.fa_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('expired', 'terminated'))
                UNION ALL
                SELECT 'goods receipt' WHERE EXISTS (
                    SELECT 1 FROM grn_lines l JOIN grns h ON h.id = l.grn_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status IN ('draft', 'inspected'))
                UNION ALL
                SELECT 'invoice' WHERE EXISTS (
                    SELECT 1 FROM invoice_lines l JOIN invoices h ON h.id = l.invoice_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('paid', 'cancelled', 'credit_note'))
                UNION ALL
                SELECT 'inventory transfer' WHERE EXISTS (
                    SELECT 1 FROM inter_site_transfer_lines l JOIN inter_site_transfers h ON h.id = l.transfer_id AND h.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.item_id = :item AND h.status NOT IN ('received', 'cancelled'))
                UNION ALL
                SELECT 'routing' WHERE EXISTS (
                    SELECT 1 FROM routing_templates WHERE tenant_id = :tenant AND finished_item_id = :item AND status = 'active')
                UNION ALL
                SELECT 'reorder policy' WHERE EXISTS (
                    SELECT 1 FROM reorder_policies WHERE tenant_id = :tenant AND item_id = :item AND is_active)
                UNION ALL
                SELECT 'replenishment request' WHERE EXISTS (
                    SELECT 1 FROM replenishment_requests WHERE tenant_id = :tenant AND item_id = :item AND status NOT IN ('fulfilled', 'cancelled'))
                UNION ALL
                SELECT 'quality inspection' WHERE EXISTS (
                    SELECT 1 FROM quality_inspections WHERE tenant_id = :tenant AND item_id = :item AND status NOT IN ('completed', 'cancelled'))
                UNION ALL
                SELECT 'nonconformance' WHERE EXISTS (
                    SELECT 1 FROM ncrs WHERE tenant_id = :tenant AND item_id = :item AND status NOT IN ('resolved', 'closed', 'scrapped'))
                """, new MapSqlParameterSource().addValue("tenant", tenantId).addValue("item", itemId), String.class);
    }

    public List<String> findReferences(UUID tenantId, UUID itemId) {
        return jdbc.queryForList("""
                SELECT 'BOM' AS dependency WHERE EXISTS (
                    SELECT 1 FROM bom_headers WHERE tenant_id = :tenant AND finished_item_id = :item
                    UNION ALL SELECT 1 FROM bom_lines WHERE tenant_id = :tenant AND component_item_id = :item)
                UNION ALL
                SELECT 'customer purchase order' WHERE EXISTS (
                    SELECT 1 FROM cpo_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'purchase requisition' WHERE EXISTS (
                    SELECT 1 FROM pr_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'supplier purchase order' WHERE EXISTS (
                    SELECT 1 FROM spo_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'production job' WHERE EXISTS (
                    SELECT 1 FROM production_jobs WHERE tenant_id = :tenant AND finished_item_id = :item
                    UNION ALL SELECT 1 FROM job_material_requirements WHERE tenant_id = :tenant AND item_id = :item
                    UNION ALL SELECT 1 FROM job_material_issues WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'inventory' WHERE EXISTS (
                    SELECT 1 FROM material_lots WHERE tenant_id = :tenant AND item_id = :item
                    UNION ALL SELECT 1 FROM stock_reservation_records WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'dispatch' WHERE EXISTS (
                    SELECT 1 FROM dispatch_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'sales RFQ' WHERE EXISTS (SELECT 1 FROM rfq_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'sales quotation' WHERE EXISTS (SELECT 1 FROM quotation_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'supplier quotation' WHERE EXISTS (SELECT 1 FROM supplier_quotation_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'frame agreement' WHERE EXISTS (SELECT 1 FROM frame_agreement_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'goods receipt' WHERE EXISTS (SELECT 1 FROM grn_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'invoice' WHERE EXISTS (SELECT 1 FROM invoice_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'inventory transfer' WHERE EXISTS (SELECT 1 FROM inter_site_transfer_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'routing' WHERE EXISTS (SELECT 1 FROM routing_templates WHERE tenant_id = :tenant AND finished_item_id = :item)
                UNION ALL
                SELECT 'replenishment' WHERE EXISTS (
                    SELECT 1 FROM reorder_policies WHERE tenant_id = :tenant AND item_id = :item
                    UNION ALL SELECT 1 FROM replenishment_requests WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'quality' WHERE EXISTS (
                    SELECT 1 FROM quality_inspections WHERE tenant_id = :tenant AND item_id = :item
                    UNION ALL SELECT 1 FROM ncrs WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'stock movement' WHERE EXISTS (SELECT 1 FROM stock_movements WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'supplier return' WHERE EXISTS (SELECT 1 FROM supplier_return_lines WHERE tenant_id = :tenant AND item_id = :item)
                UNION ALL
                SELECT 'packing specification' WHERE EXISTS (SELECT 1 FROM packing_specifications WHERE tenant_id = :tenant AND item_id = :item)
                """, new MapSqlParameterSource().addValue("tenant", tenantId).addValue("item", itemId), String.class);
    }
}
