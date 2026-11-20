package in.boss.dispatch.domain;

import in.boss.dispatch.api.DispatchDtos.*;
import in.boss.inventory.domain.LotStatus;
import in.boss.inventory.domain.LotStatusMachine;
import in.boss.inventory.domain.MovementType;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.LotRow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

final class DispatchInventoryCoordinator {

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventoryRepository;

    DispatchInventoryCoordinator(JdbcTemplate jdbc, InventoryRepository inventoryRepository) {
        this.jdbc = jdbc;
        this.inventoryRepository = inventoryRepository;
    }

    void validateCustomerPo(UUID tenantId, UUID customerId, UUID cpoId) {
        var order = jdbc.query("""
                SELECT status, customer_id FROM cpos WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, rs -> rs.next()
                        ? new OrderRow(rs.getString("status"), rs.getObject("customer_id", UUID.class))
                        : null, tenantId, cpoId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer PO was not found for this tenant");
        }
        DispatchService.requireDispatchableOrder(order.status());
        if (!customerId.equals(order.customerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Customer PO does not belong to the selected customer");
        }
    }

    UUID resolveCpoId(UUID tenantId, CreateDispatchRequest req) {
        UUID cpoId = req.cpoId();
        if (cpoId == null && req.lines() != null) {
            for (var line : req.lines()) {
                if (line.cpoLineId() != null) {
                    cpoId = jdbc.query("""
                            SELECT cpo_id FROM cpo_lines WHERE tenant_id = ? AND id = ?
                            """, rs -> rs.next() ? rs.getObject("cpo_id", UUID.class) : null,
                            tenantId, line.cpoLineId());
                    if (cpoId != null) {
                        break;
                    }
                }
            }
        }
        if (cpoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer PO is required");
        }
        return cpoId;
    }

    UUID resolveJobId(CreateDispatchRequest req) {
        Set<UUID> lineJobs = new HashSet<>();
        for (var line : req.lines()) {
            if (line.jobId() != null) {
                lineJobs.add(line.jobId());
            }
        }
        UUID jobId = req.jobId() != null ? req.jobId()
                : lineJobs.size() == 1 ? lineJobs.iterator().next() : null;
        if (jobId == null || lineJobs.stream().anyMatch(lineJob -> !jobId.equals(lineJob))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "All dispatch lines must belong to one selected production job");
        }
        return jobId;
    }

    void validateCreateLines(UUID tenantId, CreateDispatchRequest req, UUID siteId, UUID jobId, UUID cpoId) {
        if (req.sourceStoreId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A finished-goods store is required");
        }
        Map<UUID, BigDecimal> quantitiesByCpoLine = new HashMap<>();
        Map<UUID, BigDecimal> quantitiesByLot = new HashMap<>();
        Map<UUID, UUID> itemsByLot = new HashMap<>();

        for (var line : req.lines()) {
            validateLine(tenantId, cpoId, jobId, line, quantitiesByCpoLine, quantitiesByLot, itemsByLot);
        }
        quantitiesByCpoLine.forEach((lineId, quantity) ->
                validateCpoLineLimit(tenantId, cpoId, lineId, quantity));
        quantitiesByLot.forEach((lotId, quantity) -> validateFinishedGoodsLot(
                tenantId, siteId, req.sourceStoreId(), lotId, itemsByLot.get(lotId), quantity));
    }

    private void validateLine(
            UUID tenantId, UUID cpoId, UUID jobId, CreateDispatchLineRequest line,
            Map<UUID, BigDecimal> quantitiesByCpoLine,
            Map<UUID, BigDecimal> quantitiesByLot,
            Map<UUID, UUID> itemsByLot) {
        validateLineBasics(line, jobId);
        UUID itemId = resolveItemId(tenantId, line.itemId(), jobId);
        if (itemId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A finished-goods item is required for each dispatch line");
        }
        quantitiesByCpoLine.merge(line.cpoLineId(), line.quantity(), BigDecimal::add);
        quantitiesByLot.merge(line.lotId(), line.quantity(), BigDecimal::add);
        UUID previousItemId = itemsByLot.putIfAbsent(line.lotId(), itemId);
        if (previousItemId != null && !previousItemId.equals(itemId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A finished-goods lot cannot contain more than one item");
        }
        validateJobPoLine(tenantId, cpoId, line.cpoLineId(), jobId, itemId);
    }

    private void validateLineBasics(CreateDispatchLineRequest line, UUID jobId) {
        if (line.cpoLineId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A Customer PO line is required");
        }
        if (line.lotId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A finished-goods lot is required");
        }
        if (line.quantity() == null || line.quantity().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dispatch quantity must be greater than zero");
        }
        if (line.jobId() == null || !jobId.equals(line.jobId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Every dispatch line must belong to the selected production job");
        }
    }

    private UUID resolveItemId(UUID tenantId, UUID lineItemId, UUID jobId) {
        if (lineItemId != null) {
            return lineItemId;
        }
        return jdbc.query("""
                SELECT finished_item_id FROM production_jobs WHERE tenant_id = ? AND id = ?
                """, rs -> rs.next() ? rs.getObject("finished_item_id", UUID.class) : null,
                tenantId, jobId);
    }

    private void validateJobPoLine(UUID tenantId, UUID cpoId, UUID cpoLineId, UUID jobId, UUID itemId) {
        Boolean matches = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM production_jobs j
                    JOIN cpo_lines cl ON cl.tenant_id = j.tenant_id AND cl.id = j.cpo_line_id
                    WHERE j.tenant_id = ? AND j.id = ? AND j.cpo_id = ?
                      AND j.cpo_line_id = ? AND cl.cpo_id = ? AND cl.item_id = ?
                )
                """, Boolean.class, tenantId, jobId, cpoId, cpoLineId, cpoId, itemId);
        if (!Boolean.TRUE.equals(matches)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The selected PO line, item, and production job do not match");
        }
    }

    void validateFinishedGoodsLot(
            UUID tenantId, UUID siteId, UUID storeId, UUID lotId, UUID itemId, BigDecimal quantity) {
        LotRow lot = inventoryRepository.findLot(tenantId, lotId, true)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Finished-goods lot was not found for this tenant"));
        if (!LotStatus.AVAILABLE.equals(lot.status())
                || !itemId.equals(lot.itemId())
                || !storeId.equals(lot.storeId())
                || !siteId.equals(lot.siteId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Select an available finished-goods lot for the selected item, site, and store");
        }
        Boolean finishedGoods = jdbc.queryForObject("""
                SELECT i.item_type = 'finished_goods' AND s.store_type = 'finished_goods'
                  FROM items i
                  JOIN stores s ON s.tenant_id = i.tenant_id
                 WHERE i.tenant_id = ? AND i.id = ? AND s.id = ?
                """, Boolean.class, tenantId, itemId, storeId);
        if (!Boolean.TRUE.equals(finishedGoods)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dispatch requires a finished-goods item and finished-goods store");
        }
        BigDecimal pendingQty = jdbc.queryForObject("""
                SELECT COALESCE(SUM(dl.quantity), 0)
                  FROM dispatch_lines dl
                  JOIN dispatches d ON d.tenant_id = dl.tenant_id AND d.id = dl.dispatch_id
                 WHERE dl.tenant_id = ? AND dl.lot_id = ?
                   AND d.status IN ('draft', 'pending', 'pending_docs', 'packed')
                """, BigDecimal.class, tenantId, lotId);
        BigDecimal available = lot.quantity().subtract(lot.reservedQty())
                .subtract(pendingQty == null ? BigDecimal.ZERO : pendingQty);
        if (quantity.compareTo(available) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Dispatch quantity exceeds eligible finished-goods stock. Available: " + available);
        }
    }

    void validateCpoLineLimit(UUID tenantId, UUID cpoId, UUID cpoLineId, BigDecimal reqQty) {
        var cpoLineData = jdbc.queryForMap(
                """
                SELECT l.quantity, l.delivered_qty, c.status, l.cpo_id,
                       COALESCE((
                           SELECT SUM(dl.quantity)
                             FROM dispatch_lines dl
                             JOIN dispatches d ON d.tenant_id = dl.tenant_id AND d.id = dl.dispatch_id
                            WHERE dl.tenant_id = l.tenant_id AND dl.cpo_line_id = l.id
                              AND d.status IN ('draft', 'pending', 'pending_docs', 'packed')
                       ), 0) AS pending_qty
                  FROM cpo_lines l JOIN cpos c ON c.tenant_id = l.tenant_id AND c.id = l.cpo_id
                 WHERE l.tenant_id = ? AND l.id = ? FOR UPDATE OF c, l
                """,
                tenantId, cpoLineId);
        DispatchService.requireDispatchableOrder((String) cpoLineData.get("status"));
        if (!cpoId.equals(cpoLineData.get("cpo_id"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Customer PO line does not belong to the selected Customer PO");
        }
        BigDecimal orderedQty = (BigDecimal) cpoLineData.get("quantity");
        BigDecimal alreadyDelivered = (BigDecimal) cpoLineData.get("delivered_qty");
        BigDecimal pendingQty = (BigDecimal) cpoLineData.get("pending_qty");
        BigDecimal remaining = orderedQty
                .subtract(alreadyDelivered != null ? alreadyDelivered : BigDecimal.ZERO)
                .subtract(pendingQty != null ? pendingQty : BigDecimal.ZERO);
        if (reqQty != null && reqQty.compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Dispatch quantity " + reqQty + " exceeds eligible outstanding quantity " + remaining);
        }
    }

    void consumeFinishedGoods(DispatchDetail dispatch, UUID tenantId, UUID userId) {
        if (dispatch.lines() == null || dispatch.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A dispatch needs at least one PO line before it can be marked dispatched");
        }
        Map<UUID, BigDecimal> quantitiesByLot = new TreeMap<>(Comparator.comparing(UUID::toString));
        Map<UUID, UUID> itemsByLot = new HashMap<>();
        for (var line : dispatch.lines()) {
            if (line.lotId() == null || line.itemId() == null || line.quantity() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Every dispatch line must identify a finished-goods lot and quantity");
            }
            quantitiesByLot.merge(line.lotId(), line.quantity(), BigDecimal::add);
            UUID previousItemId = itemsByLot.putIfAbsent(line.lotId(), line.itemId());
            if (previousItemId != null && !previousItemId.equals(line.itemId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A finished-goods lot cannot contain more than one item");
            }
        }
        for (var allocation : quantitiesByLot.entrySet()) {
            consumeSingleLot(dispatch, tenantId, userId, allocation.getKey(), allocation.getValue(), itemsByLot.get(allocation.getKey()));
        }
    }

    private void consumeSingleLot(
            DispatchDetail dispatch, UUID tenantId, UUID userId,
            UUID lotId, BigDecimal quantity, UUID expectedItemId) {
        LotRow lot = inventoryRepository.findLot(tenantId, lotId, true)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Finished-goods lot is no longer available"));
        if (!LotStatus.AVAILABLE.equals(lot.status())
                || !lot.itemId().equals(expectedItemId)
                || !lot.storeId().equals(dispatch.sourceStoreId())
                || !lot.siteId().equals(dispatch.sourceSiteId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Finished-goods lot is no longer eligible for dispatch");
        }
        BigDecimal newQuantity = lot.quantity().subtract(quantity);
        if (quantity.signum() <= 0 || newQuantity.compareTo(lot.reservedQty()) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dispatch quantity exceeds the available finished-goods lot balance");
        }
        BigDecimal pendingOthers = jdbc.queryForObject("""
                SELECT COALESCE(SUM(dl.quantity), 0)
                  FROM dispatch_lines dl
                  JOIN dispatches d ON d.tenant_id = dl.tenant_id AND d.id = dl.dispatch_id
                 WHERE dl.tenant_id = ? AND dl.lot_id = ? AND d.id <> ?
                   AND d.status IN ('draft', 'pending', 'pending_docs', 'packed')
                """, BigDecimal.class, tenantId, lotId, dispatch.id());
        if (newQuantity.subtract(lot.reservedQty())
                .compareTo(pendingOthers == null ? BigDecimal.ZERO : pendingOthers) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another pending dispatch is already allocated to this finished-goods lot");
        }
        String nextStatus = LotStatusMachine.statusAfterMovement(
                lot.status(), MovementType.ISSUE, newQuantity, lot.reservedQty());
        inventoryRepository.updateLot(tenantId, lot.id(), newQuantity, nextStatus);
        UUID movementId = UUID.randomUUID();
        inventoryRepository.insertMovement(movementId, tenantId, MovementType.ISSUE.dbValue(),
                lot.itemId(), lot.id(), lot.storeId(), null, quantity, lot.unitCost(),
                "DISPATCH", dispatch.id(), userId,
                "Dispatched " + dispatch.dispatchNumber());
    }

    PoLineAvailability getPoLineAvailability(UUID tenantId, UUID cpoLineId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT l.quantity, l.delivered_qty,
                           COALESCE((
                               SELECT SUM(dl.quantity)
                                 FROM dispatch_lines dl
                                 JOIN dispatches d ON d.tenant_id = dl.tenant_id AND d.id = dl.dispatch_id
                                WHERE dl.tenant_id = l.tenant_id AND dl.cpo_line_id = l.id
                                  AND d.status IN ('draft', 'pending', 'pending_docs', 'packed')
                           ), 0) AS pending_qty
                      FROM cpo_lines l
                     WHERE l.tenant_id = ? AND l.id = ?
                    """, tenantId, cpoLineId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer PO line not found");
        }
        BigDecimal ordered = (BigDecimal) row.get("quantity");
        BigDecimal delivered = (BigDecimal) row.get("delivered_qty");
        BigDecimal pending = (BigDecimal) row.get("pending_qty");
        BigDecimal safeDelivered = delivered != null ? delivered : BigDecimal.ZERO;
        BigDecimal safePending = pending != null ? pending : BigDecimal.ZERO;
        BigDecimal remaining = ordered.subtract(safeDelivered).subtract(safePending).max(BigDecimal.ZERO);
        return new PoLineAvailability(cpoLineId, ordered, safeDelivered, safePending, remaining);
    }

    private record OrderRow(String status, UUID customerId) {}

    void insertLines(UUID tenantId, UUID dispatchId, UUID defaultJobId, UUID defaultCertId, List<CreateDispatchLineRequest> lines) {
        if (lines == null || lines.isEmpty()) return;
        int lineNo = 1;
        for (var lineReq : lines) {
            UUID lineJobId = lineReq.jobId() != null ? lineReq.jobId() : defaultJobId;
            UUID lineItemId = resolveItemId(tenantId, lineReq.itemId(), lineJobId);
            UUID lineUomId = resolveUomId(tenantId, lineReq.uomId(), lineItemId);

            jdbc.update("""
                    INSERT INTO dispatch_lines (
                      id, tenant_id, dispatch_id, cpo_line_id, item_id, lot_id,
                      quantity, uom_id, packing_spec_id, line_no, job_id, clearance_cert_id, created_at
                    ) VALUES (
                      gen_random_uuid(), ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, NOW()
                    )
                    """,
                    tenantId, dispatchId, lineReq.cpoLineId(), lineItemId, lineReq.lotId(),
                    lineReq.quantity(),
                    lineUomId, lineReq.packingSpecId(), lineNo++, lineJobId,
                    lineReq.clearanceCertId() != null ? lineReq.clearanceCertId() : defaultCertId
            );
        }
    }

    private UUID resolveUomId(UUID tenantId, UUID lineUomId, UUID lineItemId) {
        if (lineUomId != null) return lineUomId;
        if (lineItemId != null) {
            try {
                lineUomId = jdbc.queryForObject(
                        "SELECT base_uom_id FROM items WHERE tenant_id = ? AND id = ?",
                        UUID.class, tenantId, lineItemId);
            } catch (EmptyResultDataAccessException ignored) {}
        }
        if (lineUomId == null) {
            try {
                lineUomId = jdbc.queryForObject(
                        "SELECT id FROM units_of_measure WHERE tenant_id = ? ORDER BY is_base DESC, created_at LIMIT 1",
                        UUID.class, tenantId);
            } catch (EmptyResultDataAccessException ignored) {}
        }
        return lineUomId;
    }
}
