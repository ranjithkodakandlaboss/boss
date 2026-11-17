package in.boss.sales.domain;

import in.boss.masterdata.api.dto.CustomerDto;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.domain.CustomerService;
import in.boss.masterdata.domain.ItemService;
import in.boss.sales.api.SalesApiDtos.CpoLineResponse;
import in.boss.sales.api.SalesApiDtos.CpoResponse;
import in.boss.sales.api.SalesApiDtos.PageResponse;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class CpoLookup {

    private final JdbcTemplate jdbc;
    private final CustomerService customerService;
    private final ItemService itemService;

    public CpoLookup(JdbcTemplate jdbc, CustomerService customerService, ItemService itemService) {
        this.jdbc = jdbc;
        this.customerService = customerService;
        this.itemService = itemService;
    }

    @Transactional(readOnly = true)
    public PageResponse<CpoResponse> list(UUID customerId, UUID quotationId, String status, String query,
                                          int page, int size) {
        UUID tenantId = requireContext().tenantId();
        if (page < 0 || size < 1 || size > 100) {
            throw badRequest("Page must be non-negative and size must be between 1 and 100");
        }
        StringBuilder where = new StringBuilder(" WHERE p.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (customerId != null) {
            where.append(" AND p.customer_id = ?");
            args.add(customerId);
        }
        if (quotationId != null) {
            where.append(" AND p.quotation_id = ?");
            args.add(quotationId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND p.status = ?");
            args.add(status);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (p.cpo_number ILIKE ? OR c.name ILIKE ? OR COALESCE(p.customer_po_ref, '') ILIKE ?)");
            String like = "%" + query.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String from = " FROM cpos p JOIN customers c ON c.tenant_id = p.tenant_id AND c.id = p.customer_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<UUID> ids = jdbc.query(
                "SELECT p.id" + from + where + " ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class), args.toArray());
        List<CpoResponse> content = ids.stream().map(id -> read(tenantId, id)).toList();
        return new PageResponse<>(content, page, size, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public CpoResponse get(UUID cpoId) {
        return read(requireContext().tenantId(), cpoId);
    }

    @Transactional(readOnly = true)
    public CpoResponse findByQuotation(UUID tenantId, UUID quotationId) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM cpos WHERE tenant_id = ? AND quotation_id = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId, quotationId);
        return ids.isEmpty() ? null : read(tenantId, ids.get(0));
    }

    @Transactional(readOnly = true)
    public CpoResponse read(UUID tenantId, UUID cpoId) {
        List<Header> headers = jdbc.query("""
                SELECT p.id, p.cpo_number, p.customer_id, p.quotation_id, p.customer_po_ref, p.status,
                       p.order_date, p.required_by_date, p.payment_terms, p.notes, p.discount_amount,
                       p.source_revision, p.created_at, q.quotation_number, q.revision, q.rfq_id,
                       q.decision_channel, q.decision_note, q.decided_by, q.decided_at, r.rfq_number
                  FROM cpos p
                  LEFT JOIN quotations q ON q.tenant_id = p.tenant_id AND q.id = p.quotation_id
                  LEFT JOIN rfqs r ON r.tenant_id = q.tenant_id AND r.id = q.rfq_id
                 WHERE p.tenant_id = ? AND p.id = ?
                """, (rs, rowNum) -> new Header(
                rs.getObject("id", UUID.class),
                rs.getString("cpo_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("quotation_id", UUID.class),
                rs.getString("customer_po_ref"),
                rs.getString("status"),
                localDate(rs, "order_date"),
                localDate(rs, "required_by_date"),
                (Integer) rs.getObject("payment_terms"),
                rs.getString("notes"),
                rs.getBigDecimal("discount_amount"),
                (Integer) rs.getObject("source_revision"),
                instant(rs, "created_at"),
                rs.getString("quotation_number"),
                (Integer) rs.getObject("revision"),
                rs.getObject("rfq_id", UUID.class),
                rs.getString("decision_channel"),
                rs.getString("decision_note"),
                rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"),
                rs.getString("rfq_number")), tenantId, cpoId);
        if (headers.isEmpty()) {
            throw notFound("Customer PO not found");
        }
        Header header = headers.get(0);
        CustomerDto customer = customerService.getCustomer(header.customerId());
        List<CpoLineResponse> lines = jdbc.query("""
                SELECT l.id, l.quotation_line_id, l.item_id, l.quantity, l.unit_price, l.gst_rate,
                       l.discount_pct, l.availability, l.shortage_qty, u.symbol,
                       ql.drawing_number, ql.drawing_revision, ql.description
                  FROM cpo_lines l
                  JOIN units_of_measure u ON u.tenant_id = l.tenant_id AND u.id = l.uom_id
                  LEFT JOIN quotation_lines ql ON ql.tenant_id = l.tenant_id AND ql.id = l.quotation_line_id
                 WHERE l.tenant_id = ? AND l.cpo_id = ?
                 ORDER BY l.line_no
                """, (rs, rowNum) -> line(rs), tenantId, cpoId);
        Integer revision = header.sourceRevision() != null ? header.sourceRevision() : header.quotationRevision();
        return new CpoResponse(header.id(), header.cpoNumber(), header.customerId(), customer.getName(),
                header.quotationId(), header.quotationNumber(), revision, header.rfqId(), header.rfqNumber(),
                header.customerPoRef(), header.status(), header.orderDate(), header.requiredByDate(),
                header.paymentTerms(), header.notes(), header.discountAmount(), header.decisionChannel(),
                header.decisionNote(), header.decidedBy(), header.decidedAt(), lines, header.createdAt());
    }

    private CpoLineResponse line(ResultSet rs) throws SQLException {
        UUID itemId = rs.getObject("item_id", UUID.class);
        ItemDto item = itemService.getItem(itemId);
        BigDecimal qty = rs.getBigDecimal("quantity");
        BigDecimal price = rs.getBigDecimal("unit_price");
        BigDecimal discount = rs.getBigDecimal("discount_pct");
        return new CpoLineResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("quotation_line_id", UUID.class),
                itemId,
                item.getCode(),
                item.getName(),
                qty,
                rs.getString("symbol"),
                price,
                rs.getBigDecimal("gst_rate"),
                discount,
                SalesService.commercialLineTotal(qty, price, discount),
                rs.getString("description"),
                rs.getString("drawing_number"),
                rs.getString("drawing_revision"),
                rs.getString("availability"),
                rs.getBigDecimal("shortage_qty"));
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        return context;
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Header(UUID id, String cpoNumber, UUID customerId, UUID quotationId, String customerPoRef,
                          String status, LocalDate orderDate, LocalDate requiredByDate, Integer paymentTerms,
                          String notes, BigDecimal discountAmount, Integer sourceRevision, Instant createdAt,
                          String quotationNumber, Integer quotationRevision, UUID rfqId, String decisionChannel,
                          String decisionNote, UUID decidedBy, Instant decidedAt, String rfqNumber) {}
}
