package in.boss.sales.domain;

import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.InventoryReservationRepository.CustomerPoLine;
import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.sales.api.SalesApiDtos.CpoMaterialAvailability;
import in.boss.sales.api.SalesApiDtos.CpoMaterialAvailabilityLine;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class CpoMaterialAvailabilityService {
    private final JdbcTemplate jdbc;
    private final InventoryReservationRepository reservations;
    private final BomExplosionService explosion;
    private final CpoBomPreviewValidator bomValidator;
    private final ItemRepository itemRepository;

    public CpoMaterialAvailabilityService(JdbcTemplate jdbc, InventoryReservationRepository reservations,
                                          BomExplosionService explosion, CpoBomPreviewValidator bomValidator,
                                          ItemRepository itemRepository) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.explosion = explosion;
        this.bomValidator = bomValidator;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CpoMaterialAvailability preview(UUID cpoId) {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is required");
        }
        UUID tenantId = context.tenantId();
        List<UUID> sites = jdbc.query("SELECT site_id FROM cpos WHERE tenant_id = ? AND id = ?",
                (rs, row) -> rs.getObject("site_id", UUID.class), tenantId, cpoId);
        if (sites.isEmpty() || sites.get(0) == null || !context.canAccessSite(sites.get(0))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer PO not found");
        }
        UUID siteId = sites.get(0);
        var existing = reservations.activeCustomerPoReservations(tenantId, cpoId, false);
        if (!existing.isEmpty()) {
            boolean direct = existing.stream().anyMatch(row -> row.cpoLineId() != null);
            throw conflict(direct
                    ? "This Customer PO has direct line reservations; reconcile them before previewing materials"
                    : "This Customer PO has material reservations without a BOM snapshot; preview is unavailable");
        }

        Map<UUID, Material> totals = requirements(tenantId, cpoId);
        List<CpoMaterialAvailabilityLine> result = new ArrayList<>();
        for (Material material : totals.values()) {
            Stock stock = stock(tenantId, siteId, material.itemId());
            BigDecimal available = stock.quantity().subtract(stock.reserved());
            BigDecimal shortage = material.required().subtract(available).max(BigDecimal.ZERO);
            result.add(new CpoMaterialAvailabilityLine(material.itemId(), material.code(), material.name(),
                    material.uomId(), material.uomSymbol(), material.required(), stock.quantity(),
                    stock.reserved(), available, shortage));
        }
        return new CpoMaterialAvailability(cpoId, siteId, "CURRENT_APPROVED_FIXED_BOM",
                List.of("Current BOMs are not a historical order snapshot.",
                        "Specification-driven calculations are not supported.",
                        "Stock can change after this preview; no material is reserved."),
                List.copyOf(result));
    }

    private Map<UUID, Material> requirements(UUID tenantId, UUID cpoId) {
        Map<UUID, Material> totals = new LinkedHashMap<>();
        Set<UUID> validated = new HashSet<>();
        for (CustomerPoLine line : reservations.customerPoLines(tenantId, cpoId)) {
            BigDecimal remaining = line.quantity().subtract(line.deliveredQty());
            if (remaining.signum() <= 0) {
                continue;
            }
            if (!line.uomId().equals(bomValidator.itemBaseUom(tenantId, line.itemId()))) {
                throw conflict("Customer PO line UOM differs from the product base UOM");
            }
            List<BOMExplosionMaterialDto> materials =
                    explosion.explodeBOMExact(line.itemId(), remaining).getFlatMaterials();
            if (materials == null || materials.isEmpty()) {
                // No BOM — traded/stocked finished goods: check stock against the SKU itself.
                var item = itemRepository.findByIdAndTenantId(line.itemId(), tenantId)
                        .orElseThrow(() -> conflict("Item on Customer PO line not found"));
                if (!"finished_goods".equals(item.getItemType())) {
                    throw conflict("A Customer PO product has no approved BOM with material requirements");
                }
                List<Object[]> uomRow = jdbc.query("""
                        SELECT u.id, u.symbol FROM items i
                         JOIN units_of_measure u ON u.id = i.base_uom_id AND u.tenant_id = i.tenant_id
                        WHERE i.tenant_id = ? AND i.id = ?
                        """, (rs, r) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("symbol")},
                        tenantId, line.itemId());
                UUID uomId = uomRow.isEmpty() ? null : (UUID) uomRow.get(0)[0];
                String uomSymbol = uomRow.isEmpty() ? "" : (String) uomRow.get(0)[1];
                if (uomId == null) throw conflict("A BOM item or its base UOM is unavailable for this tenant");
                Material prev = totals.get(line.itemId());
                totals.put(line.itemId(), new Material(line.itemId(), item.getCode(), item.getName(),
                        uomId, uomSymbol,
                        remaining.add(prev == null ? BigDecimal.ZERO : prev.required())));
                continue;
            }
            bomValidator.validate(tenantId, line.itemId(), validated);
            for (BOMExplosionMaterialDto material : materials) {
                addMaterial(totals, material);
            }
        }
        if (totals.isEmpty()) {
            throw conflict("The Customer PO has no remaining material requirements");
        }
        return totals;
    }

    private void addMaterial(Map<UUID, Material> totals, BOMExplosionMaterialDto material) {
        if (material.getQty() == null || material.getQty().signum() <= 0) {
            throw conflict("BOM material quantity is below the supported calculation precision");
        }
        Material previous = totals.get(material.getItemId());
        if (previous != null && !previous.uomId().equals(material.getUomId())) {
            throw conflict("BOM material requirements use incompatible units");
        }
        totals.put(material.getItemId(), new Material(material.getItemId(), material.getItemCode(),
                material.getItemName(), material.getUomId(), material.getUomSymbol(),
                material.getQty().add(previous == null ? BigDecimal.ZERO : previous.required())));
    }

    private Stock stock(UUID tenantId, UUID siteId, UUID itemId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.quantity), 0) AS quantity,
                       COALESCE(SUM(l.reserved_qty), 0) AS reserved
                  FROM material_lots l
                  JOIN stores s ON s.id = l.store_id AND s.tenant_id = l.tenant_id
                 WHERE l.tenant_id = ? AND s.site_id = ? AND l.item_id = ?
                   AND l.status IN ('available', 'reserved')
                   AND s.store_type NOT IN ('quarantine', 'rejected')
                   AND (l.expiry_date IS NULL OR l.expiry_date >= CURRENT_DATE)
                """, (rs, row) -> new Stock(rs.getBigDecimal("quantity"), rs.getBigDecimal("reserved")),
                tenantId, siteId, itemId);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record Material(UUID itemId, String code, String name, UUID uomId, String uomSymbol,
                            BigDecimal required) {}
    private record Stock(BigDecimal quantity, BigDecimal reserved) {}
}
