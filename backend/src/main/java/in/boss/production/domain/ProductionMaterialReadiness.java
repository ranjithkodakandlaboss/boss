package in.boss.production.domain;

import in.boss.production.api.ProductionApiDtos.MaterialRequirementResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
class ProductionMaterialReadiness {

    private final JdbcTemplate jdbc;

    ProductionMaterialReadiness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String classify(UUID tenantId, UUID jobId, List<MaterialRequirementResponse> materials) {
        if (materials.isEmpty()) {
            return "pending";
        }
        boolean allCovered = materials.stream().allMatch(row -> row.netIssuedQty().compareTo(row.requiredQty()) >= 0);
        if (allCovered) {
            return "reserved";
        }
        boolean anyIssued = materials.stream().anyMatch(row -> row.netIssuedQty().signum() > 0);
        boolean stockCovers = materials.stream().allMatch(row -> usable(tenantId, row.itemId())
                .compareTo(row.requiredQty().subtract(row.netIssuedQty())) >= 0);
        if (qualityHold(tenantId, materials)) {
            return "quality_hold";
        }
        String procurement = procurementReadiness(tenantId, jobId);
        if (procurement != null && !anyIssued) {
            return procurement;
        }
        if (anyIssued) {
            return "partial";
        }
        return stockCovers ? "available" : "shortage";
    }

    static String line(BigDecimal net, BigDecimal required) {
        if (net.compareTo(required) >= 0) {
            return "reserved";
        }
        if (net.signum() > 0) {
            return "partial";
        }
        return "shortage";
    }

    private boolean qualityHold(UUID tenantId, List<MaterialRequirementResponse> materials) {
        for (MaterialRequirementResponse row : materials) {
            if (row.netIssuedQty().compareTo(row.requiredQty()) >= 0) {
                continue;
            }
            BigDecimal quarantine = sum("""
                    SELECT COALESCE(SUM(quantity), 0) FROM material_lots
                    WHERE tenant_id = ? AND item_id = ? AND status = 'quarantine'
                    """, tenantId, row.itemId());
            if (quarantine.signum() > 0 && usable(tenantId, row.itemId()).signum() == 0) {
                return true;
            }
        }
        return false;
    }

    private String procurementReadiness(UUID tenantId, UUID jobId) {
        String status = jdbc.query("""
                SELECT status FROM purchase_requisitions
                WHERE tenant_id = ? AND job_id = ? AND status NOT IN ('rejected', 'cancelled')
                ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString("status") : null, tenantId, jobId);
        if (status == null) {
            return null;
        }
        return "ordered".equals(status) ? "awaiting_grn" : "awaiting_procurement";
    }

    private BigDecimal usable(UUID tenantId, UUID itemId) {
        return sum("""
                SELECT COALESCE(SUM(quantity - reserved_qty), 0) FROM material_lots
                WHERE tenant_id = ? AND item_id = ? AND status IN ('available', 'reserved')
                """, tenantId, itemId);
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }
}
