package in.boss.sales.domain;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
class CpoBomPreviewValidator {
    private final JdbcTemplate jdbc;

    CpoBomPreviewValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void validate(UUID tenantId, UUID itemId, Set<UUID> validated) {
        validateBom(tenantId, itemId, true, new HashSet<>(), validated, 1);
    }

    UUID itemBaseUom(UUID tenantId, UUID itemId) {
        List<UUID> ids = jdbc.query("""
                SELECT i.base_uom_id FROM items i
                JOIN units_of_measure u ON u.id = i.base_uom_id AND u.tenant_id = i.tenant_id
                WHERE i.tenant_id = ? AND i.id = ?
                """, (rs, row) -> rs.getObject("base_uom_id", UUID.class), tenantId, itemId);
        if (ids.isEmpty() || ids.get(0) == null) {
            throw conflict("A BOM item or its base UOM is unavailable for this tenant");
        }
        return ids.get(0);
    }

    private void validateBom(UUID tenantId, UUID itemId, boolean required, Set<UUID> path,
                             Set<UUID> validated, int depth) {
        if (path.contains(itemId)) {
            throw conflict("Circular BOMs cannot be previewed");
        }
        if (validated.contains(itemId)) {
            return;
        }
        List<BomHeader> headers = jdbc.query("""
                SELECT id, bom_type, effective_from, effective_to
                  FROM bom_headers WHERE tenant_id = ? AND finished_item_id = ? AND status = 'approved'
                """, (rs, row) -> new BomHeader(rs.getObject("id", UUID.class), rs.getString("bom_type"),
                rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class)),
                tenantId, itemId);
        if (headers.isEmpty()) {
            if (required) {
                throw conflict("A Customer PO product has no approved BOM");
            }
            validated.add(itemId);
            return;
        }
        if (headers.size() != 1) {
            throw conflict("A product has multiple approved BOMs");
        }
        if (depth > 5) {
            throw conflict("BOM exceeds the supported depth of five levels");
        }
        BomHeader header = headers.get(0);
        validateHeader(header);
        List<BomLine> lines = jdbc.query("""
                SELECT component_item_id, uom_id, is_optional, parent_line_id, level
                  FROM bom_lines WHERE tenant_id = ? AND bom_header_id = ?
                """, (rs, row) -> new BomLine(rs.getObject("component_item_id", UUID.class),
                rs.getObject("uom_id", UUID.class), rs.getBoolean("is_optional"),
                rs.getObject("parent_line_id", UUID.class), rs.getInt("level")), tenantId, header.id());
        if (lines.isEmpty()) {
            throw conflict("An approved BOM has no material lines");
        }
        path.add(itemId);
        for (BomLine line : lines) {
            validateLine(tenantId, line);
            validateBom(tenantId, line.itemId(), false, path, validated, depth + 1);
        }
        path.remove(itemId);
        validated.add(itemId);
    }

    private void validateHeader(BomHeader header) {
        LocalDate today = jdbc.queryForObject("SELECT CURRENT_DATE", LocalDate.class);
        if ("cost_estimate".equals(header.type())
                || (header.from() != null && header.from().isAfter(today))
                || (header.to() != null && header.to().isBefore(today))) {
            throw conflict("An approved BOM is not an effective production BOM");
        }
    }

    private void validateLine(UUID tenantId, BomLine line) {
        if (line.optional() || line.parentId() != null || line.level() != 1) {
            throw conflict("Optional or hierarchical BOM lines are not supported by this preview");
        }
        if (!line.uomId().equals(itemBaseUom(tenantId, line.itemId()))) {
            throw conflict("A BOM line UOM differs from its material base UOM");
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record BomHeader(UUID id, String type, LocalDate from, LocalDate to) {}
    private record BomLine(UUID itemId, UUID uomId, boolean optional, UUID parentId, int level) {}
}
