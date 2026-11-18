package in.boss.sales.domain;

import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import in.boss.masterdata.api.dto.BOMExplosionResultDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.domain.ItemService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class QuotationMaterialCostService {

    private final JdbcTemplate jdbc;
    private final ItemService itemService;
    private final BomExplosionService bomExplosionService;

    public QuotationMaterialCostService(JdbcTemplate jdbc, ItemService itemService,
                                        BomExplosionService bomExplosionService) {
        this.jdbc = jdbc;
        this.itemService = itemService;
        this.bomExplosionService = bomExplosionService;
    }

    public BigDecimal materialCostPerUnit(UUID tenantId, UUID finishedItemId, UUID quoteUomId) {
        return materialCostDetails(tenantId, finishedItemId, quoteUomId, Map.of()).amount();
    }

    public MaterialCostResult materialCostDetails(UUID tenantId, UUID finishedItemId, UUID quoteUomId,
                                                  Map<String, BigDecimal> specifications) {
        ItemDto finishedItem = itemService.getItem(finishedItemId);
        String quoteUom = uomSymbol(tenantId, quoteUomId);
        if (finishedItem.getUomSymbol() == null || !finishedItem.getUomSymbol().equals(quoteUom)) {
            return new MaterialCostResult(null, null);
        }
        BOMExplosionResultDto bomSnapshot = bomExplosionService
                .explodeBOM(finishedItemId, BigDecimal.ONE, specifications);
        List<BOMExplosionMaterialDto> materials = bomSnapshot.getFlatMaterials();
        if (materials == null || materials.isEmpty()) return new MaterialCostResult(null, bomSnapshot);
        BigDecimal total = BigDecimal.ZERO;
        for (BOMExplosionMaterialDto material : materials) {
            ItemDto item = itemService.getItem(material.getItemId());
            if (item.getUomSymbol() == null || !item.getUomSymbol().equals(material.getUomSymbol())) {
                return new MaterialCostResult(null, bomSnapshot);
            }
            BigDecimal averageCost = jdbc.query("""
                    SELECT CASE
                             WHEN COUNT(*) FILTER (WHERE unit_cost IS NULL) > 0 THEN NULL
                             ELSE SUM(quantity * unit_cost) / NULLIF(SUM(quantity), 0)
                           END
                      FROM material_lots
                     WHERE tenant_id = ? AND item_id = ? AND status IN ('available', 'reserved')
                       AND quantity > 0
                    """, rs -> rs.next() ? rs.getBigDecimal(1) : null, tenantId, material.getItemId());
            if (averageCost == null) return new MaterialCostResult(null, bomSnapshot);
            total = total.add(material.getQty().multiply(averageCost));
        }
        return new MaterialCostResult(total.setScale(2, RoundingMode.HALF_UP), bomSnapshot);
    }

    private String uomSymbol(UUID tenantId, UUID uomId) {
        List<String> values = jdbc.query("""
                SELECT symbol FROM units_of_measure WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> rs.getString(1), tenantId, uomId);
        return values.isEmpty() ? null : values.get(0);
    }

    public record MaterialCostResult(BigDecimal amount, BOMExplosionResultDto bomSnapshot) {}
}
