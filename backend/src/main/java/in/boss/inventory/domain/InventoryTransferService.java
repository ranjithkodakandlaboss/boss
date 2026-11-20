package in.boss.inventory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.inventory.api.InventoryApiDtos.CreateTransferRequest;
import in.boss.inventory.api.InventoryApiDtos.InventoryPage;
import in.boss.inventory.api.InventoryApiDtos.ReceiveTransferRequest;
import in.boss.inventory.api.InventoryApiDtos.TransferLineResponse;
import in.boss.inventory.api.InventoryApiDtos.TransferLineRequest;
import in.boss.inventory.api.InventoryApiDtos.TransferResponse;
import in.boss.inventory.events.InventoryEventPublisher;
import in.boss.inventory.persistence.InventoryRepository;
import in.boss.inventory.persistence.LotRow;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class InventoryTransferService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> STATUSES = Set.of("pending", "in_transit", "received", "cancelled");

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventoryRepository;
    private final InventoryFeatureFlags featureFlags;
    private final InventoryReplenishmentService replenishmentService;
    private final InventoryEventPublisher events;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public InventoryTransferService(
            JdbcTemplate jdbc,
            InventoryRepository inventoryRepository,
            InventoryFeatureFlags featureFlags,
            InventoryReplenishmentService replenishmentService,
            InventoryEventPublisher events,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.inventoryRepository = inventoryRepository;
        this.featureFlags = featureFlags;
        this.replenishmentService = replenishmentService;
        this.events = events;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public TransferResponse create(CreateTransferRequest request) {
        BossContext context = requireContext();
        featureFlags.require(context.tenantId(), InventoryFeatureFlags.TRANSFERS);
        if (context.userId() == null) {
            throw new IllegalStateException("Authenticated user is required for a stock transfer");
        }
        StoreRow from = requireStore(context, request.fromStoreId(), true);
        StoreRow to = requireStore(context, request.toStoreId(), false);
        requireDifferentSites(from, to);
        List<IndexedLine> lines = lockAndPlanLines(context, from, request.lines());

        UUID transferId = UUID.randomUUID();
        String transferNumber = "TRF-" + transferId.toString().substring(0, 8).toUpperCase();
        LocalDate transferDate = request.transferDate() == null ? LocalDate.now() : request.transferDate();
        insertTransfer(context, transferId, transferNumber, from, to, transferDate, request.note());
        insertTransferLines(context.tenantId(), transferId, lines);
        dispatch(context, from, to, lines, request.note());

        auditService.record("INTER_SITE_TRANSFER", transferId, "CREATE", null,
                json(Map.of("transferNumber", transferNumber, "status", "in_transit",
                        "fromStoreId", from.id(), "toStoreId", to.id(), "lineCount", lines.size())));
        return requireTransfer(context.tenantId(), transferId);
    }

    private List<IndexedLine> lockAndPlanLines(
            BossContext context, StoreRow from, List<TransferLineRequest> requestedLines) {
        Set<UUID> distinct = new HashSet<>();
        for (TransferLineRequest line : requestedLines) {
            if (!distinct.add(line.lotId())) {
                throw new InventoryValidationException("A source lot can appear only once per transfer");
            }
        }
        Map<UUID, LotRow> lots = new java.util.HashMap<>();
        for (TransferLineRequest requested : requestedLines.stream()
                .sorted(Comparator.comparing(TransferLineRequest::lotId)).toList()) {
            lots.put(requested.lotId(), lockAndValidateLot(context, from.id(), requested));
        }
        List<IndexedLine> lines = new ArrayList<>();
        for (TransferLineRequest requested : requestedLines) {
            lines.add(new IndexedLine(UUID.randomUUID(), lots.get(requested.lotId()),
                    requested.qty().setScale(3, java.math.RoundingMode.HALF_UP)));
        }
        return lines;
    }

    private LotRow lockAndValidateLot(
            BossContext context, UUID sourceStoreId, TransferLineRequest requested) {
        LotRow lot = inventoryRepository.findLot(context.tenantId(), requested.lotId(), true)
                .orElseThrow(() -> new LotNotFoundException(requested.lotId()));
        if (!lot.storeId().equals(sourceStoreId)) {
            throw new InventoryValidationException("Every source lot must belong to the source store");
        }
        if (!context.canAccessSite(lot.siteId())) {
            throw new InventoryValidationException("Source lot is outside the caller's site");
        }
        if (!"available".equals(lot.status()) && !"reserved".equals(lot.status())) {
            throw new InventoryValidationException("Only available stock can be transferred");
        }
        if (lot.expiryDate() != null && lot.expiryDate().isBefore(LocalDate.now())) {
            throw new InventoryValidationException("Expired stock cannot be transferred");
        }
        BigDecimal qty = requested.qty().setScale(3, java.math.RoundingMode.HALF_UP);
        if (lot.quantity().subtract(lot.reservedQty()).compareTo(qty) < 0) {
            throw new InventoryValidationException("Transfer quantity exceeds unreserved stock");
        }
        return lot;
    }

    private void insertTransfer(
            BossContext context, UUID id, String number, StoreRow from, StoreRow to,
            LocalDate date, String note) {
        jdbc.update("""
                INSERT INTO inter_site_transfers (
                  id, tenant_id, transfer_number, from_site_id, to_site_id,
                  from_store_id, to_store_id, status, transfer_date, notes, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'in_transit', ?, ?, ?)
                """, id, context.tenantId(), number, from.siteId(), to.siteId(),
                from.id(), to.id(), date, blankToNull(note), context.userId());
    }

    private void insertTransferLines(UUID tenantId, UUID transferId, List<IndexedLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            IndexedLine line = lines.get(i);
            LotRow lot = line.lot();
            jdbc.update("""
                    INSERT INTO inter_site_transfer_lines (
                      id, tenant_id, transfer_id, item_id, lot_id, quantity, uom_id, line_no
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, line.id(), tenantId, transferId, lot.itemId(), lot.id(),
                    line.quantity(), lot.uomId(), i + 1);
        }
    }

    private void dispatch(
            BossContext context, StoreRow from, StoreRow to, List<IndexedLine> lines, String note) {
        for (IndexedLine line : lines) {
            LotRow lot = line.lot();
            BigDecimal nextQty = lot.quantity().subtract(line.quantity());
            String nextStatus = LotStatusMachine.statusAfterMovement(
                    lot.status(), MovementType.TRANSFER_OUT, nextQty, lot.reservedQty());
            inventoryRepository.updateLot(context.tenantId(), lot.id(), nextQty, nextStatus);
            if (!lot.status().equals(nextStatus)) {
                auditService.record("LOT", lot.id(), "STATUS",
                        json(Map.of("status", LotStatus.toApi(lot.status()))),
                        json(Map.of("status", LotStatus.toApi(nextStatus))));
            }
            UUID movementId = UUID.randomUUID();
            inventoryRepository.insertMovement(
                    movementId, context.tenantId(), MovementType.TRANSFER_OUT.dbValue(),
                    lot.itemId(), lot.id(), from.id(), to.id(), line.quantity(), lot.unitCost(),
                    "TRANSFER", line.id(), context.userId(), blankToNull(note));
            Map<String, Object> movementSnapshot = movementSnapshot(
                    movementId, lot.id(), lot.itemId(), MovementType.TRANSFER_OUT, line.quantity());
            auditService.record("STOCK_MOVEMENT", movementId, "CREATE", null, json(movementSnapshot));
            events.movementCreated(movementId, movementSnapshot);
            replenishmentService.checkAfterStockChange(context.tenantId(), lot.itemId(), from.id());
        }
    }

    private static void requireDifferentSites(StoreRow from, StoreRow to) {
        if (from.id().equals(to.id()) || from.siteId().equals(to.siteId())) {
            throw new InventoryValidationException("A transfer must move stock between different sites");
        }
    }

    @Transactional(readOnly = true)
    public InventoryPage<TransferResponse> list(
            UUID fromStoreId, UUID toStoreId, String status, int page, int size) {
        BossContext context = requireContext();
        checkPage(page, size);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toLowerCase();
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) {
            throw new InventoryValidationException("Unknown transfer status: " + status);
        }
        StringBuilder where = new StringBuilder(" WHERE t.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(context.tenantId());
        if (context.siteId() != null) {
            where.append(" AND (t.from_site_id = ? OR t.to_site_id = ?)");
            args.add(context.siteId());
            args.add(context.siteId());
        }
        if (fromStoreId != null) {
            where.append(" AND t.from_store_id = ?");
            args.add(fromStoreId);
        }
        if (toStoreId != null) {
            where.append(" AND t.to_store_id = ?");
            args.add(toStoreId);
        }
        if (normalizedStatus != null) {
            where.append(" AND t.status = ?");
            args.add(normalizedStatus);
        }
        List<UUID> ids = jdbc.query("""
                SELECT t.id FROM inter_site_transfers t
                """ + where + " ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                append(args, size, page * size).toArray());
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inter_site_transfers t" + where,
                Long.class, args.toArray());
        List<TransferResponse> transfers = ids.stream()
                .map(id -> requireTransfer(context.tenantId(), id))
                .filter(transfer -> context.canAccessSite(storeSite(context.tenantId(), transfer.fromStoreId()))
                        || context.canAccessSite(storeSite(context.tenantId(), transfer.toStoreId())))
                .toList();
        return new InventoryPage<>(transfers, page, count == null ? 0 : count);
    }

    public TransferResponse receive(UUID transferId, ReceiveTransferRequest request) {
        BossContext context = requireContext();
        TransferHeader transfer = findHeader(context.tenantId(), transferId, true)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!context.canAccessSite(transfer.toSiteId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Transfer can only be received at its destination site");
        }
        if (!"in_transit".equals(transfer.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only in-transit transfers can be received");
        }

        List<IndexedLine> lines = transferLines(context.tenantId(), transferId);
        String receivedNote = request == null ? null : blankToNull(request.receivedNote());
        String destinationStoreType = inventoryRepository.storeLocation(
                        context.tenantId(), transfer.toStoreId())
                .map(InventoryRepository.StoreLocation::storeType)
                .orElseThrow(() -> new IllegalStateException(
                        "Transfer destination store is missing: " + transfer.toStoreId()));
        for (IndexedLine line : lines) {
            LotRow source = line.lot();
            UUID destinationLotId = UUID.randomUUID();
            String destinationLotNumber = "TRF-" + line.id().toString().substring(0, 8).toUpperCase();
            String destinationStatus = switch (destinationStoreType) {
                case "quarantine" -> LotStatus.QUARANTINE;
                case "rejected" -> LotStatus.REJECTED;
                default -> source.expiryDate() != null
                        && source.expiryDate().isBefore(LocalDate.now())
                        ? LotStatus.QUARANTINE : LotStatus.AVAILABLE;
            };
            inventoryRepository.insertLot(destinationLotId, context.tenantId(), source.itemId(),
                    transfer.toStoreId(), destinationLotNumber, source.batchNumber(), line.quantity(),
                    source.unitCost(), destinationStatus, source.expiryDate());
            Map<String, Object> lotSnapshot = Map.of(
                    "id", destinationLotId, "itemId", source.itemId(),
                    "storeId", transfer.toStoreId(), "qty", line.quantity(),
                    "status", destinationStatus);
            auditService.record("LOT", destinationLotId, "CREATE", null, json(lotSnapshot));
            events.lotCreated(destinationLotId, lotSnapshot);
            UUID movementId = UUID.randomUUID();
            inventoryRepository.insertMovement(
                    movementId, context.tenantId(), MovementType.TRANSFER_IN.dbValue(),
                    source.itemId(), destinationLotId, transfer.fromStoreId(), transfer.toStoreId(),
                    line.quantity(), source.unitCost(), "TRANSFER", line.id(), context.userId(),
                    receivedNote);
            Map<String, Object> movementSnapshot = movementSnapshot(
                    movementId, destinationLotId, source.itemId(), MovementType.TRANSFER_IN, line.quantity());
            auditService.record("STOCK_MOVEMENT", movementId, "CREATE", null, json(movementSnapshot));
            events.movementCreated(movementId, movementSnapshot);
            jdbc.update("""
                    UPDATE inter_site_transfer_lines SET received_qty = ?
                     WHERE tenant_id = ? AND id = ?
                    """, line.quantity(), context.tenantId(), line.id());
            replenishmentService.checkAfterStockChange(
                    context.tenantId(), source.itemId(), transfer.toStoreId());
        }
        jdbc.update("""
                UPDATE inter_site_transfers
                   SET status = 'received', updated_at = NOW()
                 WHERE tenant_id = ? AND id = ?
                """, context.tenantId(), transferId);
        auditService.record("INTER_SITE_TRANSFER", transferId, "RECEIVE",
                json(Map.of("status", "in_transit")),
                json(Map.of("status", "received", "receivedNote",
                        receivedNote == null ? "" : receivedNote)));
        return requireTransfer(context.tenantId(), transferId);
    }

    private TransferResponse requireTransfer(UUID tenantId, UUID transferId) {
        TransferHeader header = findHeader(tenantId, transferId, false)
                .orElseThrow(() -> new IllegalStateException("Transfer was not readable: " + transferId));
        List<TransferLineResponse> lines = jdbc.query("""
                SELECT l.id, l.lot_id, i.code AS item_code, i.name AS item_name,
                       l.quantity, u.symbol AS uom_symbol
                  FROM inter_site_transfer_lines l
                  JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                  JOIN units_of_measure u ON u.id = l.uom_id AND u.tenant_id = l.tenant_id
                 WHERE l.tenant_id = ? AND l.transfer_id = ?
                 ORDER BY l.line_no
                """, (rs, rowNum) -> new TransferLineResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("item_name"),
                rs.getBigDecimal("quantity"),
                rs.getString("uom_symbol")), tenantId, transferId);
        return new TransferResponse(header.id(), header.transferNumber(),
                header.fromStoreId(), header.fromStoreName(),
                header.toStoreId(), header.toStoreName(), header.status(), header.transferDate(),
                lines, header.createdAt(),
                "received".equals(header.status()) ? header.updatedAt() : null);
    }

    private java.util.Optional<TransferHeader> findHeader(UUID tenantId, UUID transferId, boolean lock) {
        return jdbc.query("""
                SELECT t.id, t.transfer_number, t.from_store_id, fs.name AS from_store_name,
                       t.to_store_id, ts.name AS to_store_name, t.from_site_id, t.to_site_id,
                       t.status, t.transfer_date, t.created_at, t.updated_at
                  FROM inter_site_transfers t
                  JOIN stores fs ON fs.id = t.from_store_id AND fs.tenant_id = t.tenant_id
                  JOIN stores ts ON ts.id = t.to_store_id AND ts.tenant_id = t.tenant_id
                 WHERE t.tenant_id = ? AND t.id = ?
                """ + (lock ? " FOR UPDATE OF t" : ""),
                rs -> rs.next() ? java.util.Optional.of(new TransferHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("transfer_number"),
                        rs.getObject("from_store_id", UUID.class),
                        rs.getString("from_store_name"),
                        rs.getObject("to_store_id", UUID.class),
                        rs.getString("to_store_name"),
                        rs.getObject("from_site_id", UUID.class),
                        rs.getObject("to_site_id", UUID.class),
                        rs.getString("status"),
                        rs.getDate("transfer_date").toLocalDate(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                        : java.util.Optional.empty(), tenantId, transferId);
    }

    private List<IndexedLine> transferLines(UUID tenantId, UUID transferId) {
        return jdbc.query("""
                SELECT l.id, l.lot_id, l.quantity
                  FROM inter_site_transfer_lines l
                 WHERE l.tenant_id = ? AND l.transfer_id = ?
                 ORDER BY l.line_no
                """, (rs, rowNum) -> {
            UUID lineId = rs.getObject("id", UUID.class);
            UUID lotId = rs.getObject("lot_id", UUID.class);
            LotRow lot = inventoryRepository.findLot(tenantId, lotId, true)
                    .orElseThrow(() -> new LotNotFoundException(lotId));
            return new IndexedLine(lineId, lot, rs.getBigDecimal("quantity"));
        }, tenantId, transferId);
    }

    private StoreRow requireStore(BossContext context, UUID storeId, boolean enforceSiteAccess) {
        List<StoreRow> rows = jdbc.query("""
                SELECT id, site_id, name FROM stores WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> new StoreRow(rs.getObject("id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getString("name")),
                context.tenantId(), storeId);
        return rows.stream().findFirst().map(row -> {
            if (enforceSiteAccess && !context.canAccessSite(row.siteId())) {
                throw new InventoryValidationException("Store is outside the caller's site");
            }
            return row;
        }).orElseThrow(() -> new InventoryValidationException(
                "Store not found for this tenant: " + storeId));
    }

    private UUID storeSite(UUID tenantId, UUID storeId) {
        return inventoryRepository.storeSiteId(tenantId, storeId)
                .orElseThrow(() -> new IllegalStateException("Transfer store is missing: " + storeId));
    }

    private static BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return context;
    }

    private static List<Object> append(List<Object> source, Object... values) {
        List<Object> result = new ArrayList<>(source);
        result.addAll(List.of(values));
        return result;
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InventoryValidationException("Invalid transfer page or size");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> movementSnapshot(
            UUID id, UUID lotId, UUID itemId, MovementType type, BigDecimal quantity) {
        return Map.of("id", id, "lotId", lotId, "itemId", itemId,
                "movementType", type.dbValue(), "qty", quantity);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize transfer audit data", e);
        }
    }

    private record StoreRow(UUID id, UUID siteId, String name) {}

    private record TransferHeader(
            UUID id, String transferNumber, UUID fromStoreId, String fromStoreName,
            UUID toStoreId, String toStoreName, UUID fromSiteId, UUID toSiteId,
            String status, LocalDate transferDate, java.time.Instant createdAt,
            java.time.Instant updatedAt) {}

    private record IndexedLine(UUID id, LotRow lot, BigDecimal quantity) {}
}
