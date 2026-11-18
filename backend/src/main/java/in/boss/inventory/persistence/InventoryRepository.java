package in.boss.inventory.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InventoryRepository {

    private static final String LOT_FROM = """
            FROM material_lots ml
            JOIN items i ON i.id = ml.item_id AND i.tenant_id = ml.tenant_id
            JOIN stores s ON s.id = ml.store_id AND s.tenant_id = ml.tenant_id
            JOIN units_of_measure u ON u.id = i.base_uom_id AND u.tenant_id = ml.tenant_id
            """;

    private static final String LOT_SELECT = """
            SELECT ml.id, ml.item_id, i.code AS item_code, i.name AS item_name,
                   ml.store_id, s.name AS store_name, s.site_id,
                   ml.batch_number, ml.lot_number,
                   (SELECT sm.reference_id FROM stock_movements sm
                     WHERE sm.lot_id = ml.id AND sm.tenant_id = ml.tenant_id
                       AND sm.reference_type = 'GRN'
                     ORDER BY sm.created_at ASC, sm.id ASC LIMIT 1) AS grn_line_id,
                   ml.quantity, ml.reserved_qty, ml.unit_cost, i.base_uom_id, u.symbol,
                   ml.status, ml.expiry_date, ml.created_at
            """;

    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GrnLineRef> findGrnLine(UUID tenantId, UUID grnLineId) {
        List<GrnLineRef> rows = jdbc.query("""
                SELECT item_id, store_id
                  FROM grn_lines
                 WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new GrnLineRef(
                        rs.getObject("item_id", UUID.class),
                        rs.getObject("store_id", UUID.class)),
                tenantId, grnLineId);
        return rows.stream().findFirst();
    }

    public boolean itemExists(UUID tenantId, UUID itemId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM items WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, itemId);
        return count != null && count > 0;
    }

    public Optional<UUID> storeSiteId(UUID tenantId, UUID storeId) {
        List<UUID> rows = jdbc.query(
                "SELECT site_id FROM stores WHERE tenant_id = ? AND id = ?",
                (rs, rowNum) -> rs.getObject("site_id", UUID.class),
                tenantId, storeId);
        return rows.stream().findFirst();
    }

    public void insertLot(UUID id, UUID tenantId, UUID itemId, UUID storeId, String lotNumber,
                          String batchNumber, BigDecimal quantity, BigDecimal unitCost,
                          String status, LocalDate expiryDate) {
        jdbc.update("""
                INSERT INTO material_lots (
                  id, tenant_id, item_id, store_id, lot_number, batch_number,
                  quantity, reserved_qty, unit_cost, status, expiry_date, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, NOW())
                """,
                id, tenantId, itemId, storeId, lotNumber, batchNumber,
                quantity, unitCost, status, expiryDate == null ? null : Date.valueOf(expiryDate));
    }

    public void insertMovement(UUID id, UUID tenantId, String movementType, UUID itemId, UUID lotId,
                               UUID fromStoreId, UUID toStoreId, BigDecimal quantity, BigDecimal unitCost,
                               String referenceType, UUID referenceId, UUID performedBy, String notes) {
        jdbc.update("""
                INSERT INTO stock_movements (
                  id, tenant_id, movement_type, item_id, lot_id, from_store_id, to_store_id,
                  quantity, unit_cost, reference_type, reference_id, performed_by, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantId, movementType, itemId, lotId, fromStoreId, toStoreId,
                quantity, unitCost, referenceType, referenceId, performedBy, notes);
    }

    public void updateLot(UUID tenantId, UUID lotId, BigDecimal quantity, String status) {
        int updated = jdbc.update("""
                UPDATE material_lots
                   SET quantity = ?, status = ?, updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """, quantity, status, tenantId, lotId);
        if (updated != 1) {
            throw new IllegalStateException("Lot update did not match a single row: " + lotId);
        }
    }

    public Optional<LotRow> findLot(UUID tenantId, UUID lotId, boolean lock) {
        String sql = LOT_SELECT + LOT_FROM + " WHERE ml.tenant_id = ? AND ml.id = ?"
                + (lock ? " FOR UPDATE OF ml" : "");
        List<LotRow> rows = jdbc.query(sql, lotMapper(), tenantId, lotId);
        return rows.stream().findFirst();
    }

    public List<LotRow> listLots(UUID tenantId, UUID itemId, UUID storeId, String status,
                                 UUID siteId, int page, int size) {
        Filter filter = lotFilter(tenantId, itemId, storeId, status, siteId);
        String sql = LOT_SELECT + LOT_FROM + filter.sql()
                + " ORDER BY ml.created_at DESC, ml.id DESC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(filter.args());
        args.add(size);
        args.add(page * size);
        return jdbc.query(sql, lotMapper(), args.toArray());
    }

    public long countLots(UUID tenantId, UUID itemId, UUID storeId, String status, UUID siteId) {
        Filter filter = lotFilter(tenantId, itemId, storeId, status, siteId);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) " + LOT_FROM + filter.sql(), Long.class, filter.args().toArray());
        return count == null ? 0L : count;
    }

    public Optional<MovementRow> findMovement(UUID tenantId, UUID movementId) {
        List<MovementRow> rows = jdbc.query("""
                SELECT id, lot_id, movement_type, quantity, reference_type, reference_id,
                       performed_by, notes, created_at
                  FROM stock_movements
                 WHERE tenant_id = ? AND id = ?
                """, movementMapper(), tenantId, movementId);
        return rows.stream().findFirst();
    }

    public List<MovementRow> listMovements(UUID tenantId, UUID lotId, LocalDate from, LocalDate to,
                                           int page, int size) {
        Filter filter = movementFilter(tenantId, lotId, from, to);
        String sql = """
                SELECT id, lot_id, movement_type, quantity, reference_type, reference_id,
                       performed_by, notes, created_at
                  FROM stock_movements
                """ + filter.sql() + " ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(filter.args());
        args.add(size);
        args.add(page * size);
        return jdbc.query(sql, movementMapper(), args.toArray());
    }

    public long countMovements(UUID tenantId, UUID lotId, LocalDate from, LocalDate to) {
        Filter filter = movementFilter(tenantId, lotId, from, to);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_movements " + filter.sql(), Long.class, filter.args().toArray());
        return count == null ? 0L : count;
    }

    public List<PositionRow> positions(UUID tenantId, UUID itemId, UUID storeId, UUID siteId,
                                       boolean belowReorderPoint, int page, int size) {
        Filter grouped = positionQuery(tenantId, itemId, storeId, siteId, belowReorderPoint);
        String sql = grouped.sql() + " ORDER BY item_code, store_name LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(grouped.args());
        args.add(size);
        args.add(page * size);
        return jdbc.query(sql, positionMapper(), args.toArray());
    }

    public long countPositions(UUID tenantId, UUID itemId, UUID storeId, UUID siteId,
                               boolean belowReorderPoint) {
        Filter grouped = positionQuery(tenantId, itemId, storeId, siteId, belowReorderPoint);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + grouped.sql() + ") positions",
                Long.class, grouped.args().toArray());
        return count == null ? 0L : count;
    }

    private Filter positionQuery(UUID tenantId, UUID itemId, UUID storeId, UUID siteId,
                                 boolean belowReorderPoint) {
        StringBuilder sql = new StringBuilder("""
                WITH moved AS (
                    SELECT lot.tenant_id, lot.item_id, lot.store_id,
                           MAX(sm.created_at) AS last_moved_at
                      FROM stock_movements sm
                      JOIN material_lots lot
                        ON lot.id = sm.lot_id AND lot.tenant_id = sm.tenant_id
                     WHERE lot.tenant_id = ? AND lot.status IN ('available', 'reserved')
                     GROUP BY lot.tenant_id, lot.item_id, lot.store_id
                ), stock_rows AS (
                    SELECT ml.tenant_id, ml.item_id, ml.store_id,
                           ml.quantity AS total_qty, ml.reserved_qty,
                           0::numeric AS in_transit_qty,
                           ml.quantity * COALESCE(ml.unit_cost, 0) AS valuation_amt,
                           moved.last_moved_at
                      FROM material_lots ml
                      LEFT JOIN moved
                        ON moved.tenant_id = ml.tenant_id AND moved.item_id = ml.item_id
                       AND moved.store_id = ml.store_id
                     WHERE ml.tenant_id = ? AND ml.status IN ('available', 'reserved')
                    UNION ALL
                    SELECT t.tenant_id, l.item_id, t.to_store_id AS store_id,
                           0::numeric AS total_qty, 0::numeric AS reserved_qty,
                           l.quantity - COALESCE(l.received_qty, 0) AS in_transit_qty,
                           0::numeric AS valuation_amt, NULL::timestamptz AS last_moved_at
                      FROM inter_site_transfers t
                      JOIN inter_site_transfer_lines l
                        ON l.transfer_id = t.id AND l.tenant_id = t.tenant_id
                     WHERE t.tenant_id = ? AND t.status = 'in_transit'
                )
                SELECT p.item_id, i.code AS item_code, i.name AS item_name,
                       p.store_id, s.name AS store_name,
                       SUM(p.total_qty) AS total_qty,
                       SUM(p.reserved_qty) AS reserved_qty,
                       SUM(p.total_qty - p.reserved_qty) AS available_qty,
                       SUM(p.in_transit_qty) AS in_transit_qty,
                       SUM(p.valuation_amt) AS valuation_amt,
                       COALESCE(rp.reorder_point, i.reorder_qty) AS reorder_point,
                       u.symbol AS uom_symbol,
                       MAX(p.last_moved_at) AS last_moved_at
                  FROM stock_rows p
                  JOIN items i ON i.id = p.item_id AND i.tenant_id = p.tenant_id
                  JOIN stores s ON s.id = p.store_id AND s.tenant_id = p.tenant_id
                  JOIN units_of_measure u ON u.id = i.base_uom_id AND u.tenant_id = p.tenant_id
                  LEFT JOIN reorder_policies rp
                    ON rp.tenant_id = p.tenant_id AND rp.item_id = p.item_id
                   AND rp.store_id = p.store_id AND rp.is_active
                 WHERE p.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(tenantId);
        args.add(tenantId);
        args.add(tenantId);
        if (itemId != null) {
            sql.append(" AND p.item_id = ?");
            args.add(itemId);
        }
        if (storeId != null) {
            sql.append(" AND p.store_id = ?");
            args.add(storeId);
        }
        if (siteId != null) {
            sql.append(" AND s.site_id = ?");
            args.add(siteId);
        }
        sql.append("""
                 GROUP BY p.item_id, i.code, i.name, p.store_id, s.name, u.symbol,
                          rp.reorder_point, i.reorder_qty
                """);
        if (belowReorderPoint) {
            sql.append("""
                     HAVING COALESCE(rp.reorder_point, i.reorder_qty) IS NOT NULL
                        AND SUM(p.total_qty - p.reserved_qty)
                            < COALESCE(rp.reorder_point, i.reorder_qty)
                    """);
        }
        return new Filter(sql.toString(), args);
    }

    private Filter lotFilter(UUID tenantId, UUID itemId, UUID storeId, String status, UUID siteId) {
        StringBuilder sql = new StringBuilder(" WHERE ml.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (itemId != null) {
            sql.append(" AND ml.item_id = ?");
            args.add(itemId);
        }
        if (storeId != null) {
            sql.append(" AND ml.store_id = ?");
            args.add(storeId);
        }
        if (status != null) {
            sql.append(" AND ml.status = ?");
            args.add(status);
        }
        if (siteId != null) {
            sql.append(" AND s.site_id = ?");
            args.add(siteId);
        }
        return new Filter(sql.toString(), args);
    }

    private Filter movementFilter(UUID tenantId, UUID lotId, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder(" WHERE tenant_id = ? AND lot_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(lotId);
        if (from != null) {
            sql.append(" AND created_at::date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND created_at::date <= ?");
            args.add(Date.valueOf(to));
        }
        return new Filter(sql.toString(), args);
    }

    private RowMapper<LotRow> lotMapper() {
        return (rs, rowNum) -> new LotRow(
                uuid(rs, "id"),
                uuid(rs, "item_id"),
                rs.getString("item_code"),
                rs.getString("item_name"),
                uuid(rs, "store_id"),
                rs.getString("store_name"),
                uuid(rs, "site_id"),
                rs.getString("batch_number"),
                rs.getString("lot_number"),
                uuid(rs, "grn_line_id"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("reserved_qty"),
                rs.getBigDecimal("unit_cost"),
                uuid(rs, "base_uom_id"),
                rs.getString("symbol"),
                rs.getString("status"),
                date(rs, "expiry_date"),
                instant(rs, "created_at"));
    }

    private RowMapper<MovementRow> movementMapper() {
        return (rs, rowNum) -> new MovementRow(
                uuid(rs, "id"),
                uuid(rs, "lot_id"),
                rs.getString("movement_type"),
                rs.getBigDecimal("quantity"),
                rs.getString("reference_type"),
                uuid(rs, "reference_id"),
                uuid(rs, "performed_by"),
                rs.getString("notes"),
                instant(rs, "created_at"));
    }

    private RowMapper<PositionRow> positionMapper() {
        return (rs, rowNum) -> new PositionRow(
                uuid(rs, "item_id"),
                rs.getString("item_code"),
                rs.getString("item_name"),
                uuid(rs, "store_id"),
                rs.getString("store_name"),
                rs.getBigDecimal("total_qty"),
                rs.getBigDecimal("reserved_qty"),
                rs.getBigDecimal("available_qty"),
                rs.getBigDecimal("in_transit_qty"),
                rs.getBigDecimal("valuation_amt"),
                rs.getBigDecimal("reorder_point"),
                rs.getString("uom_symbol"),
                instant(rs, "last_moved_at"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Filter(String sql, List<Object> args) {}
}
