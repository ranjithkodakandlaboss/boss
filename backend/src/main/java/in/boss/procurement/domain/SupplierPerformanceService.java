package in.boss.procurement.domain;

import in.boss.procurement.api.ProcurementApiDtos.SupplierPerformanceResponse;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@PreAuthorize("hasAnyRole('OWNER','PURCHASE_MANAGER','OPS_MANAGER')")
public class SupplierPerformanceService {

    private final JdbcTemplate jdbc;

    public SupplierPerformanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SupplierPerformanceResponse forSupplier(UUID supplierId) {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is required");
        }
        String name = jdbc.query("""
                SELECT name FROM suppliers WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getString("name") : null, context.tenantId(), supplierId);
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }
        return jdbc.query("""
                SELECT COUNT(DISTINCT spo_id) AS orders,
                       COUNT(*) FILTER (WHERE promised_date IS NOT NULL AND actual_date <= promised_date) AS on_time,
                       COUNT(*) FILTER (WHERE promised_date IS NOT NULL) AS dated,
                       COUNT(*) AS receipts,
                       COALESCE(SUM(quantity_received), 0) AS received,
                       COALESCE(SUM(quantity_rejected), 0) AS rejected,
                       AVG(actual_date - promised_date) FILTER (WHERE promised_date IS NOT NULL) AS lead
                FROM supplier_performance_records
                WHERE tenant_id = ? AND supplier_id = ?
                """, rs -> {
            if (!rs.next() || rs.getLong("receipts") == 0) {
                return new SupplierPerformanceResponse(supplierId, name, 0, null, null, null);
            }
            long dated = rs.getLong("dated");
            BigDecimal onTime = dated == 0 ? null : BigDecimal.valueOf(rs.getLong("on_time"))
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(dated), 2, RoundingMode.HALF_UP);
            BigDecimal received = rs.getBigDecimal("received");
            BigDecimal quality = received.signum() == 0 ? BigDecimal.ZERO
                    : rs.getBigDecimal("rejected").multiply(BigDecimal.valueOf(100))
                    .divide(received, 2, RoundingMode.HALF_UP);
            return new SupplierPerformanceResponse(
                    supplierId, name, rs.getInt("receipts"), onTime, quality, rs.getBigDecimal("lead"));
        }, context.tenantId(), supplierId);
    }
}
