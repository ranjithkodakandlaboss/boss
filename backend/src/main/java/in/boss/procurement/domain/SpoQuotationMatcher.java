package in.boss.procurement.domain;

import in.boss.procurement.api.ProcurementApiDtos.CreateSpoLineRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

final class SpoQuotationMatcher {

    private final JdbcTemplate jdbc;

    SpoQuotationMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SelectedQuotation requireSelectedQuotation(
            UUID tenantId, UUID quotationId, UUID prId, UUID supplierId) {
        List<SelectedQuotation> quotations = jdbc.query("""
                SELECT q.id, q.pr_id, q.supplier_id, q.status, q.payment_terms, q.payment_terms_text,
                       q.freight_amount, q.tax_amount, p.site_id, p.status AS pr_status
                FROM supplier_quotations q
                JOIN purchase_requisitions p
                  ON p.id = q.pr_id AND p.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.id = ?
                FOR UPDATE OF q, p
                """, (rs, rowNum) -> new SelectedQuotation(
                rs.getObject("id", UUID.class),
                rs.getObject("pr_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("status"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("payment_terms_text"),
                rs.getBigDecimal("freight_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getObject("site_id", UUID.class),
                rs.getString("pr_status")), tenantId, quotationId);
        if (quotations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Selected supplier quotation not found: " + quotationId);
        }
        SelectedQuotation quotation = quotations.get(0);
        if (!"selected".equals(quotation.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SPO must be created from a selected supplier quotation");
        }
        if (!prId.equals(quotation.prId()) || !supplierId.equals(quotation.supplierId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SPO purchase requisition and supplier must match the selected quotation");
        }
        return quotation;
    }

    ValidatedSpoLine requireMatchingQuotationLine(
            UUID tenantId, SelectedQuotation quotation, CreateSpoLineRequest request) {
        List<ValidatedSpoLine> lines = jdbc.query("""
                SELECT ql.id AS quotation_line_id, ql.pr_line_id, ql.item_id,
                       ql.quantity AS quotation_quantity, ql.uom_id, ql.unit_price, ql.tax_rate,
                       pl.quantity AS pr_quantity, pl.item_id AS pr_item_id, pl.uom_id AS pr_uom_id
                FROM supplier_quotation_lines ql
                JOIN pr_lines pl
                  ON pl.id = ql.pr_line_id AND pl.tenant_id = ql.tenant_id
                WHERE ql.tenant_id = ? AND ql.sq_id = ? AND ql.pr_line_id = ?
                FOR UPDATE OF ql, pl
                """, (rs, rowNum) -> new ValidatedSpoLine(
                rs.getObject("quotation_line_id", UUID.class),
                rs.getObject("pr_line_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getBigDecimal("quotation_quantity"),
                rs.getObject("uom_id", UUID.class),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("pr_quantity"),
                rs.getObject("pr_item_id", UUID.class),
                rs.getObject("pr_uom_id", UUID.class),
                rs.getBigDecimal("tax_rate"),
                null), tenantId, quotation.id(), request.prLineId());
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Every SPO line must match a line in the selected supplier quotation");
        }
        if (lines.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Selected quotation contains duplicate lines for a purchase requisition line");
        }
        ValidatedSpoLine line = lines.get(0);
        if (!line.itemId().equals(request.itemId()) || !line.itemId().equals(line.prItemId())
                || !line.uomId().equals(request.uomId()) || !line.uomId().equals(line.prUomId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SPO item and unit of measure must match the selected quotation and PR line");
        }
        if (line.unitPrice().compareTo(request.unitPrice()) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SPO unit price must match the selected quotation");
        }
        return line.withRequest(request);
    }

    record SelectedQuotation(
            UUID id, UUID prId, UUID supplierId, String status,
            Integer paymentTerms, String paymentTermsText,
            BigDecimal freightAmount, BigDecimal taxAmount,
            UUID siteId, String prStatus) {}

    record ValidatedSpoLine(
            UUID quotationLineId, UUID prLineId, UUID itemId, BigDecimal quotationQuantity,
            UUID uomId, BigDecimal unitPrice, BigDecimal prQuantity, UUID prItemId,
            UUID prUomId, BigDecimal taxRate, CreateSpoLineRequest request) {

        ValidatedSpoLine withRequest(CreateSpoLineRequest request) {
            return new ValidatedSpoLine(
                    quotationLineId, prLineId, itemId, quotationQuantity,
                    uomId, unitPrice, prQuantity, prItemId, prUomId, taxRate, request);
        }
    }
}
