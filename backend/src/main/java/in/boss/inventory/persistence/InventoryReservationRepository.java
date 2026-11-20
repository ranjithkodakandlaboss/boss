package in.boss.inventory.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InventoryReservationRepository {

    private final JdbcTemplate jdbc;

    public InventoryReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> lockProductionJob(UUID tenantId, UUID jobId) {
        List<UUID> sites = jdbc.query("""
                SELECT site_id
                  FROM production_jobs
                 WHERE tenant_id = ? AND id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getObject("site_id", UUID.class), tenantId, jobId);
        return sites.stream().findFirst();
    }

    public Optional<ProductionJobSnapshot> lockProductionJobSnapshot(UUID tenantId, UUID jobId) {
        List<ProductionJobSnapshot> rows = jdbc.query("""
                SELECT site_id, finished_item_id, quantity
                  FROM production_jobs
                 WHERE tenant_id = ? AND id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new ProductionJobSnapshot(
                rs.getObject("site_id", UUID.class),
                rs.getObject("finished_item_id", UUID.class),
                rs.getBigDecimal("quantity")), tenantId, jobId);
        return rows.stream().findFirst();
    }

    public boolean hasJobMaterialRequirements(UUID tenantId, UUID jobId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM job_material_requirements
                 WHERE tenant_id = ? AND job_id = ?
                """, Integer.class, tenantId, jobId);
        return count != null && count > 0;
    }

    public void insertJobMaterialRequirements(
            UUID tenantId, UUID jobId, List<JobMaterialSnapshotLine> lines) {
        for (JobMaterialSnapshotLine line : lines) {
            jdbc.update("""
                    INSERT INTO job_material_requirements
                      (tenant_id, job_id, item_id, required_qty, uom_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, tenantId, jobId, line.itemId(), line.requiredQty(), line.uomId());
        }
    }

    public List<JobMaterialNeed> lockUnissuedJobMaterials(UUID tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT item_id, required_qty - issued_qty + returned_qty AS remaining_qty
                  FROM job_material_requirements
                 WHERE tenant_id = ? AND job_id = ?
                   AND required_qty - issued_qty + returned_qty > 0
                 ORDER BY item_id, id
                 FOR UPDATE
                """, (rs, rowNum) -> new JobMaterialNeed(
                rs.getObject("item_id", UUID.class), rs.getBigDecimal("remaining_qty")),
                tenantId, jobId);
    }

    public Optional<CustomerPoHeader> lockCustomerPo(UUID tenantId, UUID cpoId) {
        List<CustomerPoHeader> rows = jdbc.query("""
                SELECT site_id, status, required_by_date
                  FROM cpos
                 WHERE tenant_id = ? AND id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new CustomerPoHeader(
                rs.getObject("site_id", UUID.class),
                rs.getString("status"),
                rs.getObject("required_by_date", LocalDate.class)), tenantId, cpoId);
        return rows.stream().findFirst();
    }

    public Optional<CustomerPoLine> lockCustomerPoLine(UUID tenantId, UUID cpoLineId) {
        List<CustomerPoLine> rows = jdbc.query("""
                SELECT cpo_id, item_id, quantity, delivered_qty, uom_id
                  FROM cpo_lines
                 WHERE tenant_id = ? AND id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new CustomerPoLine(
                rs.getObject("cpo_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("delivered_qty"),
                rs.getObject("uom_id", UUID.class)), tenantId, cpoLineId);
        return rows.stream().findFirst();
    }

    public List<CustomerPoLine> customerPoLines(UUID tenantId, UUID cpoId) {
        return jdbc.query("""
                SELECT cpo_id, item_id, quantity, delivered_qty, uom_id
                  FROM cpo_lines
                 WHERE tenant_id = ? AND cpo_id = ?
                 ORDER BY line_no
                """, (rs, rowNum) -> new CustomerPoLine(
                rs.getObject("cpo_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("delivered_qty"),
                rs.getObject("uom_id", UUID.class)), tenantId, cpoId);
    }

    public BigDecimal activeCustomerPoReservedQty(UUID tenantId, UUID cpoLineId) {
        BigDecimal quantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0)
                  FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_type = 'cpo'
                   AND cpo_line_id = ? AND status = 'active'
                """, BigDecimal.class, tenantId, cpoLineId);
        return quantity == null ? BigDecimal.ZERO : quantity;
    }

    public void insertCustomerPoReservation(UUID tenantId, UUID lotId, UUID itemId,
                                            BigDecimal quantity, UUID cpoId, UUID cpoLineId) {
        jdbc.update("""
                INSERT INTO stock_reservation_records
                  (tenant_id, lot_id, item_id, reserved_qty, reference_type, reference_id,
                   cpo_line_id, status)
                VALUES (?, ?, ?, ?, 'cpo', ?, ?, 'active')
                """, tenantId, lotId, itemId, quantity, cpoId, cpoLineId);
    }

    public List<CustomerPoReservationRow> activeCustomerPoReservations(
            UUID tenantId, UUID cpoId, boolean lock) {
        String sql = """
                SELECT id, lot_id, item_id, cpo_line_id, reserved_qty
                  FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_type = 'cpo'
                   AND reference_id = ? AND status = 'active'
                 ORDER BY lot_id, id
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, rowNum) -> new CustomerPoReservationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getObject("cpo_line_id", UUID.class),
                rs.getBigDecimal("reserved_qty")), tenantId, cpoId);
    }

    public boolean hasActiveReservations(UUID tenantId, UUID jobId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_type = 'production_job'
                   AND reference_id = ? AND status = 'active'
                """, Integer.class, tenantId, jobId);
        return count != null && count > 0;
    }

    public List<UUID> lockReservableLotIds(UUID tenantId, UUID itemId, UUID storeId, UUID siteId) {
        return jdbc.query("""
                SELECT ml.id
                  FROM material_lots ml
                  JOIN stores s ON s.id = ml.store_id AND s.tenant_id = ml.tenant_id
                 WHERE ml.tenant_id = ? AND ml.item_id = ? AND ml.store_id = ?
                   AND s.site_id = ?
                   AND ml.status IN ('available', 'reserved')
                   AND ml.quantity > ml.reserved_qty
                   AND (ml.expiry_date IS NULL OR ml.expiry_date >= CURRENT_DATE)
                 ORDER BY ml.expiry_date ASC NULLS LAST, ml.received_at ASC NULLS LAST, ml.id
                 FOR UPDATE OF ml
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId, itemId, storeId, siteId);
    }

    public List<UUID> lockReservableLotIdsAtSite(UUID tenantId, UUID itemId, UUID siteId) {
        return jdbc.query("""
                SELECT ml.id
                  FROM material_lots ml
                  JOIN stores s ON s.id = ml.store_id AND s.tenant_id = ml.tenant_id
                 WHERE ml.tenant_id = ? AND ml.item_id = ? AND s.site_id = ?
                   AND ml.status IN ('available', 'reserved')
                   AND ml.quantity > ml.reserved_qty
                   AND (ml.expiry_date IS NULL OR ml.expiry_date >= CURRENT_DATE)
                 ORDER BY ml.expiry_date ASC NULLS LAST, ml.received_at ASC NULLS LAST,
                          ml.store_id, ml.id
                 FOR UPDATE OF ml
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId, itemId, siteId);
    }

    public void updateLotBalance(UUID tenantId, UUID lotId, BigDecimal quantity,
                                 BigDecimal reservedQty, String status) {
        int updated = jdbc.update("""
                UPDATE material_lots
                   SET quantity = ?, reserved_qty = ?, status = ?, updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """, quantity, reservedQty, status, tenantId, lotId);
        if (updated != 1) {
            throw new IllegalStateException("Lot balance update did not match a single row: " + lotId);
        }
    }

    public void insertReservation(UUID tenantId, UUID lotId, UUID itemId, BigDecimal quantity,
                                  UUID jobId) {
        jdbc.update("""
                INSERT INTO stock_reservation_records
                  (tenant_id, lot_id, item_id, reserved_qty, reference_type, reference_id, status)
                VALUES (?, ?, ?, ?, 'production_job', ?, 'active')
                """, tenantId, lotId, itemId, quantity, jobId);
    }

    public List<ReservationRow> activeReservations(UUID tenantId, UUID jobId, UUID lotId,
                                                    boolean lock) {
        String sql = """
                SELECT id, lot_id, item_id, reserved_qty
                  FROM stock_reservation_records
                 WHERE tenant_id = ? AND reference_type = 'production_job'
                   AND reference_id = ? AND status = 'active'
                """ + (lotId == null ? "" : " AND lot_id = ?")
                + " ORDER BY id" + (lock ? " FOR UPDATE" : "");
        List<Object> args = new ArrayList<>(List.of(tenantId, jobId));
        if (lotId != null) {
            args.add(lotId);
        }
        return jdbc.query(sql, (rs, rowNum) -> new ReservationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("reserved_qty")), args.toArray());
    }

    public void reduceReservation(UUID tenantId, UUID reservationId, BigDecimal quantity,
                                  boolean fullyConsumed) {
        int updated = jdbc.update("""
                UPDATE stock_reservation_records
                   SET reserved_qty = CASE WHEN ? THEN reserved_qty ELSE reserved_qty - ? END,
                       status = CASE WHEN ? THEN 'consumed' ELSE status END,
                       released_at = CASE WHEN ? THEN NOW() ELSE released_at END
                 WHERE tenant_id = ? AND id = ? AND status = 'active'
                   AND reserved_qty >= ?
                """, fullyConsumed, quantity, fullyConsumed, fullyConsumed,
                tenantId, reservationId, quantity);
        if (updated != 1) {
            throw new IllegalStateException("Reservation update did not match a single active row");
        }
    }

    public void releaseReservation(UUID tenantId, UUID reservationId) {
        jdbc.update("""
                UPDATE stock_reservation_records
                   SET status = 'released', released_at = NOW()
                 WHERE tenant_id = ? AND id = ? AND status = 'active'
                """, tenantId, reservationId);
    }

    public BigDecimal jobIssuedQty(UUID tenantId, UUID jobId, UUID lotId) {
        BigDecimal quantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE issue_type WHEN 'issue' THEN quantity ELSE -quantity END), 0)
                  FROM job_material_issues
                 WHERE tenant_id = ? AND job_id = ? AND lot_id = ?
                   AND issue_type IN ('issue', 'return')
                """, BigDecimal.class, tenantId, jobId, lotId);
        return quantity == null ? BigDecimal.ZERO : quantity;
    }

    public void insertJobMaterialIssue(UUID tenantId, UUID jobId, UUID itemId, UUID lotId,
                                       BigDecimal quantity, UUID uomId, String issueType,
                                       UUID storeId, UUID performedBy, String notes) {
        jdbc.update("""
                INSERT INTO job_material_issues
                  (tenant_id, job_id, item_id, lot_id, quantity, uom_id, issue_type,
                   store_id, issued_by, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, jobId, itemId, lotId, quantity, uomId, issueType,
                storeId, performedBy, notes);
    }

    public List<ActiveJobReservation> activeProductionReservations(UUID tenantId, UUID siteId) {
        String sql = """
                SELECT r.reference_id AS job_id, j.job_number, j.site_id, r.item_id,
                       i.code AS item_code, i.name AS item_name,
                       SUM(r.reserved_qty) AS reserved_qty
                  FROM stock_reservation_records r
                  JOIN production_jobs j ON j.id = r.reference_id AND j.tenant_id = r.tenant_id
                  JOIN items i ON i.id = r.item_id AND i.tenant_id = r.tenant_id
                 WHERE r.tenant_id = ? AND r.reference_type = 'production_job' AND r.status = 'active'
                """ + (siteId == null ? "" : " AND j.site_id = ? ") + """
                 GROUP BY r.reference_id, j.job_number, j.site_id, r.item_id, i.code, i.name
                 ORDER BY j.job_number, i.code
                """;
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (siteId != null) {
            args.add(siteId);
        }
        return jdbc.query(sql, (rs, rowNum) -> new ActiveJobReservation(
                rs.getObject("job_id", UUID.class),
                rs.getString("job_number"),
                rs.getObject("site_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("item_name"),
                rs.getBigDecimal("reserved_qty")), args.toArray());
    }

    public List<ActiveLotReservation> activeProductionLotReservations(UUID tenantId, UUID jobId, UUID siteId) {
        String sql = """
                SELECT r.reference_id AS job_id, r.lot_id, r.item_id, r.reserved_qty
                  FROM stock_reservation_records r
                  JOIN production_jobs j ON j.id = r.reference_id AND j.tenant_id = r.tenant_id
                 WHERE r.tenant_id = ? AND r.reference_type = 'production_job' AND r.status = 'active'
                   AND r.reference_id = ?
                """ + (siteId == null ? "" : " AND j.site_id = ? ") + """
                 ORDER BY r.lot_id
                """;
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(jobId);
        if (siteId != null) {
            args.add(siteId);
        }
        return jdbc.query(sql, (rs, rowNum) -> new ActiveLotReservation(
                rs.getObject("job_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("reserved_qty")), args.toArray());
    }

    public record ActiveJobReservation(
            UUID jobId,
            String jobNumber,
            UUID siteId,
            UUID itemId,
            String itemCode,
            String itemName,
            BigDecimal reservedQty
    ) {}

    public record ActiveLotReservation(
            UUID jobId,
            UUID lotId,
            UUID itemId,
            BigDecimal reservedQty
    ) {}

    public List<LotReservationRow> activeLotReservations(UUID tenantId, UUID lotId) {
        return jdbc.query("""
                SELECT r.id, r.reference_type, r.reference_id, r.reserved_qty,
                       CASE r.reference_type
                         WHEN 'production_job' THEN j.job_number
                         WHEN 'cpo' THEN p.cpo_number
                         ELSE NULL
                       END AS document_number
                  FROM stock_reservation_records r
                  LEFT JOIN production_jobs j ON j.id = r.reference_id
                                              AND j.tenant_id = r.tenant_id
                                              AND r.reference_type = 'production_job'
                  LEFT JOIN cpos p ON p.id = r.reference_id
                                   AND p.tenant_id = r.tenant_id
                                   AND r.reference_type = 'cpo'
                 WHERE r.tenant_id = ? AND r.lot_id = ? AND r.status = 'active'
                 ORDER BY r.reference_type, document_number
                """, (rs, rowNum) -> new LotReservationRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("reference_type"),
                        rs.getObject("reference_id", UUID.class),
                        rs.getBigDecimal("reserved_qty"),
                        rs.getString("document_number")),
                tenantId, lotId);
    }

    public record LotReservationRow(
            UUID id,
            String referenceType,
            UUID referenceId,
            BigDecimal reservedQty,
            String documentNumber
    ) {}

    public record CustomerPoHeader(UUID siteId, String status, LocalDate requiredByDate) {}

    public record JobMaterialNeed(UUID itemId, BigDecimal remainingQty) {}

    public record ProductionJobSnapshot(UUID siteId, UUID finishedItemId, BigDecimal quantity) {}

    public record JobMaterialSnapshotLine(UUID itemId, BigDecimal requiredQty, UUID uomId) {}

    public record CustomerPoLine(UUID cpoId, UUID itemId, BigDecimal quantity,
                                 BigDecimal deliveredQty, UUID uomId) {}
}
