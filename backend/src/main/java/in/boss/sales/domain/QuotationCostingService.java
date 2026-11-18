package in.boss.sales.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.domain.ItemService;
import in.boss.sales.api.SalesApiDtos.CostRate;
import in.boss.sales.api.SalesApiDtos.CreateQuotationLineRequest;
import in.boss.sales.api.SalesApiDtos.QuotationCostingConfigRequest;
import in.boss.sales.api.SalesApiDtos.QuotationCostingConfigResponse;
import in.boss.sales.api.SalesApiDtos.QuotationCostingSnapshot;
import in.boss.sales.api.SalesApiDtos.QuotationLineCosting;
import in.boss.shared.audit.AuditService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.COMMERCIAL_DATA_READ;
import static in.boss.shared.security.BossPermissions.OWNER;

@Service
public class QuotationCostingService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final TypeReference<List<CostRate>> COST_RATES = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ItemService itemService;
    private final BomExplosionService bomExplosionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public QuotationCostingService(JdbcTemplate jdbc, ItemService itemService,
                                   BomExplosionService bomExplosionService, AuditService auditService,
                                   ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.itemService = itemService;
        this.bomExplosionService = bomExplosionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize(COMMERCIAL_DATA_READ)
    @Transactional(readOnly = true)
    public QuotationCostingConfigResponse getConfig() {
        return loadConfig(requireContext().tenantId());
    }

    @PreAuthorize(OWNER)
    @Transactional
    public QuotationCostingConfigResponse saveConfig(QuotationCostingConfigRequest request) {
        BossContext context = requireContext();
        validateRates(request.labourRates(), "labour");
        validateRates(request.machineRates(), "machine");
        if (request.minimumMarginPct().compareTo(request.targetMarginPct()) > 0) {
            throw badRequest("Minimum margin cannot exceed the target margin");
        }
        QuotationCostingConfigResponse before = loadConfig(context.tenantId());
        jdbc.update("""
                INSERT INTO quotation_costing_config
                  (tenant_id, labour_rates, machine_rates, overhead_per_unit, consumables_per_unit,
                   packaging_per_unit, transport_per_unit, target_margin_pct, minimum_margin_pct,
                   approval_threshold_pct, updated_by, updated_at)
                VALUES (?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (tenant_id) DO UPDATE SET
                  labour_rates = EXCLUDED.labour_rates, machine_rates = EXCLUDED.machine_rates,
                  overhead_per_unit = EXCLUDED.overhead_per_unit,
                  consumables_per_unit = EXCLUDED.consumables_per_unit,
                  packaging_per_unit = EXCLUDED.packaging_per_unit,
                  transport_per_unit = EXCLUDED.transport_per_unit,
                  target_margin_pct = EXCLUDED.target_margin_pct,
                  minimum_margin_pct = EXCLUDED.minimum_margin_pct,
                  approval_threshold_pct = EXCLUDED.approval_threshold_pct,
                  updated_by = EXCLUDED.updated_by, updated_at = NOW()
                """, context.tenantId(), toJson(request.labourRates()), toJson(request.machineRates()),
                request.overheadPerUnit(), request.consumablesPerUnit(), request.packagingPerUnit(),
                request.transportPerUnit(), request.targetMarginPct(), request.minimumMarginPct(),
                request.approvalThresholdPct(), context.userId());
        QuotationCostingConfigResponse result = loadConfig(context.tenantId());
        auditService.record("QUOTATION_COSTING_CONFIG", context.tenantId(), "UPDATE",
                toJson(before), toJson(result));
        return result;
    }

    @Transactional(readOnly = true)
    public QuotationCostingSnapshot calculate(UUID tenantId, List<CreateQuotationLineRequest> lines,
                                               BigDecimal discountAmount) {
        QuotationCostingConfigResponse config = loadConfig(tenantId);
        Map<String, BigDecimal> labourRates = ratesByName(config.labourRates());
        Map<String, BigDecimal> machineRates = ratesByName(config.machineRates());
        BigDecimal materialTotal = BigDecimal.ZERO;
        BigDecimal labourTotal = BigDecimal.ZERO;
        BigDecimal machineTotal = BigDecimal.ZERO;
        BigDecimal overheadTotal = BigDecimal.ZERO;
        BigDecimal consumablesTotal = BigDecimal.ZERO;
        BigDecimal packagingTotal = BigDecimal.ZERO;
        BigDecimal transportTotal = BigDecimal.ZERO;
        BigDecimal netRevenue = BigDecimal.ZERO;
        boolean available = true;
        List<String> unavailableReasons = new ArrayList<>();
        List<QuotationLineCosting> lineSnapshots = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            CreateQuotationLineRequest line = lines.get(index);
            BigDecimal materialCost = materialCostPerUnit(tenantId, line.itemId(), line.uomId());
            if (materialCost == null) {
                available = false;
                unavailableReasons.add("Line " + (index + 1) + " has no approved BOM or fully valued inventory");
                materialCost = BigDecimal.ZERO;
            } else {
                materialCost = money(materialCost.multiply(line.qty()));
            }
            BigDecimal labourCost = hoursCost(line.labourHours(), labourRates, "labour");
            BigDecimal machineCost = hoursCost(line.machineHours(), machineRates, "machine");
            BigDecimal overheadCost = money(config.overheadPerUnit().multiply(line.qty()));
            BigDecimal consumablesCost = money(config.consumablesPerUnit().multiply(line.qty()));
            BigDecimal packagingCost = money(config.packagingPerUnit().multiply(line.qty()));
            BigDecimal transportCost = money(config.transportPerUnit().multiply(line.qty()));
            materialTotal = materialTotal.add(materialCost);
            labourTotal = labourTotal.add(labourCost);
            machineTotal = machineTotal.add(machineCost);
            overheadTotal = overheadTotal.add(overheadCost);
            consumablesTotal = consumablesTotal.add(consumablesCost);
            packagingTotal = packagingTotal.add(packagingCost);
            transportTotal = transportTotal.add(transportCost);
            netRevenue = netRevenue.add(SalesService.commercialLineTotal(line.qty(), line.unitPrice(), line.discountPct()));
            lineSnapshots.add(new QuotationLineCosting(index + 1, materialCost, labourCost, machineCost,
                    overheadCost, consumablesCost, packagingCost, transportCost,
                    safeHours(line.labourHours()), safeHours(line.machineHours())));
        }

        netRevenue = money(netRevenue.subtract(discountAmount == null ? BigDecimal.ZERO : discountAmount).max(BigDecimal.ZERO));
        BigDecimal totalCost = money(materialTotal.add(labourTotal).add(machineTotal).add(overheadTotal)
                .add(consumablesTotal).add(packagingTotal).add(transportTotal));
        BigDecimal grossProfit = money(netRevenue.subtract(totalCost));
        BigDecimal marginPct = netRevenue.signum() == 0 ? null
                : grossProfit.multiply(HUNDRED).divide(netRevenue, 4, RoundingMode.HALF_UP);
        boolean requiresApproval = marginPct == null
                || marginPct.compareTo(config.minimumMarginPct()) < 0
                || marginPct.compareTo(config.approvalThresholdPct()) < 0;
        return new QuotationCostingSnapshot(available, "weighted-average on-hand inventory valuation",
                money(materialTotal), money(labourTotal), money(machineTotal), money(overheadTotal),
                money(consumablesTotal), money(packagingTotal), money(transportTotal), totalCost,
                netRevenue, grossProfit, marginPct, config.targetMarginPct(), config.minimumMarginPct(),
                config.approvalThresholdPct(), requiresApproval, List.copyOf(unavailableReasons),
                List.copyOf(lineSnapshots));
    }

    public BigDecimal materialCostPerUnit(UUID tenantId, UUID finishedItemId, UUID quoteUomId) {
        ItemDto finishedItem = itemService.getItem(finishedItemId);
        String quoteUom = uomSymbol(tenantId, quoteUomId);
        if (finishedItem.getUomSymbol() == null || !finishedItem.getUomSymbol().equals(quoteUom)) {
            return null;
        }
        List<BOMExplosionMaterialDto> materials =
                bomExplosionService.explodeBOM(finishedItemId, BigDecimal.ONE).getFlatMaterials();
        if (materials == null || materials.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (BOMExplosionMaterialDto material : materials) {
            ItemDto item = itemService.getItem(material.getItemId());
            if (item.getUomSymbol() == null || !item.getUomSymbol().equals(material.getUomSymbol())) return null;
            BigDecimal averageCost = jdbc.query("""
                    SELECT SUM(quantity * COALESCE(unit_cost, 0)) / NULLIF(SUM(quantity), 0)
                      FROM material_lots
                     WHERE tenant_id = ? AND item_id = ? AND status IN ('available', 'reserved')
                       AND quantity > 0
                    """, rs -> rs.next() ? rs.getBigDecimal(1) : null, tenantId, material.getItemId());
            if (averageCost == null) return null;
            total = total.add(material.getQty().multiply(averageCost));
        }
        return money(total);
    }

    private QuotationCostingConfigResponse loadConfig(UUID tenantId) {
        return jdbc.query("""
                SELECT labour_rates::text, machine_rates::text, overhead_per_unit, consumables_per_unit,
                       packaging_per_unit, transport_per_unit, target_margin_pct, minimum_margin_pct,
                       approval_threshold_pct
                  FROM quotation_costing_config WHERE tenant_id = ?
                """, rs -> {
            if (!rs.next()) return defaults();
            return new QuotationCostingConfigResponse(
                    parseRates(rs.getString(1)), parseRates(rs.getString(2)),
                    rs.getBigDecimal("overhead_per_unit"), rs.getBigDecimal("consumables_per_unit"),
                    rs.getBigDecimal("packaging_per_unit"), rs.getBigDecimal("transport_per_unit"),
                    rs.getBigDecimal("target_margin_pct"), rs.getBigDecimal("minimum_margin_pct"),
                    rs.getBigDecimal("approval_threshold_pct"));
        }, tenantId);
    }

    private QuotationCostingConfigResponse defaults() {
        return new QuotationCostingConfigResponse(List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20.00"), new BigDecimal("10.00"),
                new BigDecimal("15.00"));
    }

    private String uomSymbol(UUID tenantId, UUID uomId) {
        List<String> values = jdbc.query("""
                SELECT symbol FROM units_of_measure WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> rs.getString(1), tenantId, uomId);
        return values.isEmpty() ? null : values.get(0);
    }

    private static BigDecimal hoursCost(Map<String, BigDecimal> hours, Map<String, BigDecimal> rates, String type) {
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : safeHours(hours).entrySet()) {
            BigDecimal rate = rates.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (rate == null) throw badRequest("Unknown " + type + " activity: " + entry.getKey());
            total = total.add(entry.getValue().multiply(rate));
        }
        return money(total);
    }

    private static Map<String, BigDecimal> safeHours(Map<String, BigDecimal> hours) {
        return hours == null ? Map.of() : hours;
    }

    private static Map<String, BigDecimal> ratesByName(List<CostRate> rates) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (CostRate rate : rates) result.put(rate.name().toLowerCase(Locale.ROOT), rate.ratePerHour());
        return result;
    }

    private static void validateRates(List<CostRate> rates, String type) {
        Map<String, Boolean> seen = new HashMap<>();
        for (CostRate rate : rates) {
            String name = rate.name().trim().toLowerCase(Locale.ROOT);
            if (rate.ratePerHour().signum() < 0 || seen.put(name, true) != null) {
                throw badRequest("Each " + type + " rate needs a unique name and a non-negative rate");
            }
        }
    }

    private List<CostRate> parseRates(String json) {
        try {
            return objectMapper.readValue(json, COST_RATES);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read quotation costing configuration", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize quotation costing data", exception);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return context;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
