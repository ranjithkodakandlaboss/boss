package in.boss.dispatch.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.dispatch.api.DispatchDtos.*;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PackingSpecService {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final DispatchService dispatchService;

    public PackingSpecService(
            JdbcTemplate jdbc,
            AuditService auditService,
            ObjectMapper objectMapper,
            DispatchService dispatchService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.dispatchService = dispatchService;
    }

    public PagedPackingSpecs listPackingSpecs(UUID itemId, Boolean isActive, int page, int size) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        StringBuilder sql = new StringBuilder("""
                SELECT ps.id, ps.item_id, it.code AS item_code, it.name AS item_name,
                       ps.name, ps.packing_type, ps.units_per_pack, ps.gross_weight_kg,
                       ps.dimensions_cm, ps.notes, ps.created_at
                FROM packing_specifications ps
                LEFT JOIN items it ON it.id = ps.item_id AND it.tenant_id = ps.tenant_id
                WHERE ps.tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (itemId != null) {
            params.add(itemId);
            sql.append(" AND ps.item_id = ?");
        }

        sql.append(" ORDER BY ps.created_at DESC");

        List<PackingSpecDto> all = jdbc.query(sql.toString(), (rs, i) -> mapPackingSpec(rs), params.toArray());
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<PackingSpecDto> paged = all.subList(from, to);
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 1;

        return new PagedPackingSpecs(paged, total, totalPages, page, size);
    }

    @Transactional
    public PackingSpecDto createPackingSpec(CreatePackingSpecRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        if (req.itemId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item ID is required");
        }

        UUID specId = UUID.randomUUID();
        String name = req.packingTypeLabel() != null ? req.packingTypeLabel() : "Standard Packaging";
        String dimJson = dimensionsJson(req.packDimensions());

        jdbc.update("""
                INSERT INTO packing_specifications (
                  id, tenant_id, item_id, name, packing_type, units_per_pack,
                  gross_weight_kg, dimensions_cm, notes, created_at
                ) VALUES (
                  ?, ?, ?, ?, ?, ?,
                  ?, ?::jsonb, ?, NOW()
                )
                """,
                specId, tenantId, req.itemId(), name, req.packingTypeLabel(),
                req.unitsPerPack(), req.packWeight(), dimJson, req.handlingInstructions()
        );

        return getPackingSpec(specId);
    }

    public PackingSpecDto getPackingSpec(UUID id) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        List<PackingSpecDto> list = jdbc.query("""
                SELECT ps.id, ps.item_id, it.code AS item_code, it.name AS item_name,
                       ps.name, ps.packing_type, ps.units_per_pack, ps.gross_weight_kg,
                       ps.dimensions_cm, ps.notes, ps.created_at
                FROM packing_specifications ps
                LEFT JOIN items it ON it.id = ps.item_id AND it.tenant_id = ps.tenant_id
                WHERE ps.tenant_id = ? AND ps.id = ?
                """, (rs, i) -> mapPackingSpec(rs), tenantId, id);

        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Packing spec not found");
        }
        return list.get(0);
    }

    @Transactional
    public PackingSpecDto updatePackingSpec(UUID id, CreatePackingSpecRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        String dimJson = dimensionsJson(req.packDimensions());

        jdbc.update("""
                UPDATE packing_specifications
                SET packing_type = COALESCE(?, packing_type),
                    units_per_pack = COALESCE(?, units_per_pack),
                    gross_weight_kg = COALESCE(?, gross_weight_kg),
                    dimensions_cm = COALESCE(?::jsonb, dimensions_cm),
                    notes = COALESCE(?, notes)
                WHERE tenant_id = ? AND id = ?
                """,
                req.packingTypeLabel(), req.unitsPerPack(), req.packWeight(),
                dimJson, req.handlingInstructions(), tenantId, id
        );

        return getPackingSpec(id);
    }

    @Transactional
    public DispatchPackingRecordDto recordDispatchPacking(UUID dispatchId, RecordPackingRequest req) {
        BossContext ctx = requireContext();
        UUID tenantId = ctx.tenantId();

        var dispatch = dispatchService.getDispatch(dispatchId);
        if (!canRecordPacking(dispatch.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Packing can only be recorded for pending or draft dispatches");
        }

        UUID pkgId = UUID.randomUUID();
        String pkgCode = "PKG-" + (dispatch.packages().size() + 1);
        String pkgType = req.packingTypeLabel() != null ? req.packingTypeLabel() : "Standard Pack";

        jdbc.update("""
                INSERT INTO dispatch_packages (
                  id, tenant_id, dispatch_id, package_code, package_type, product_description,
                  quantity, uom, weight_kg, packing_notes, created_at
                ) VALUES (
                  ?, ?, ?, ?, ?, ?,
                  ?, 'units', ?, ?, NOW()
                )
                """,
                pkgId, tenantId, dispatchId, pkgCode, pkgType,
                "Packed consignment for " + dispatch.dispatchNumber(),
                req.actualPacksCount() != null ? BigDecimal.valueOf(req.actualPacksCount()) : BigDecimal.ONE,
                req.actualGrossWeight(), req.packingNote()
        );

        if (req.packingSpecId() != null) {
            jdbc.update("""
                    UPDATE dispatch_lines
                    SET packing_spec_id = ?
                    WHERE tenant_id = ? AND dispatch_id = ?
                    """, req.packingSpecId(), tenantId, dispatchId);
        }

        auditService.record("dispatch", dispatchId, "packing_recorded", null,
                toJson(Map.of("packageCode", pkgCode, "packsCount", req.actualPacksCount() != null ? req.actualPacksCount() : 1)));

        return new DispatchPackingRecordDto(
                pkgId,
                dispatchId,
                req.packingSpecId(),
                req.packingTypeId(),
                pkgType,
                req.actualPacksCount() != null ? req.actualPacksCount() : 1,
                req.actualGrossWeight(),
                req.packingNote(),
                Instant.now()
        );
    }

    private static boolean canRecordPacking(String status) {
        return "pending".equalsIgnoreCase(status)
                || "pending_docs".equalsIgnoreCase(status)
                || "draft".equalsIgnoreCase(status);
    }

    private String dimensionsJson(Dimensions dimensions) {
        if (dimensions == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(dimensions);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private PackingSpecDto mapPackingSpec(ResultSet rs) throws SQLException {
        Dimensions dims = null;
        String dimStr = rs.getString("dimensions_cm");
        if (dimStr != null && !dimStr.isBlank()) {
            try {
                dims = objectMapper.readValue(dimStr, Dimensions.class);
            } catch (Exception ignored) {}
        }

        return new PackingSpecDto(
                (UUID) rs.getObject("id"),
                getUuid(rs, "item_id"),
                rs.getString("item_code"),
                rs.getString("item_name"),
                null,
                rs.getString("packing_type"),
                rs.getInt("units_per_pack"),
                rs.getBigDecimal("gross_weight_kg"),
                "kg",
                dims,
                null,
                rs.getString("notes"),
                true,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static UUID getUuid(ResultSet rs, String col) throws SQLException {
        Object obj = rs.getObject(col);
        return obj instanceof UUID u ? u : null;
    }

    private BossContext requireContext() {
        BossContext ctx = BossContextHolder.get();
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "BossContext required");
        }
        return ctx;
    }
}
