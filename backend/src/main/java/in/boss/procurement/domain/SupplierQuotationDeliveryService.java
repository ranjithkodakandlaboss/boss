package in.boss.procurement.domain;

import in.boss.procurement.email.ProcurementMailSender;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class SupplierQuotationDeliveryService {

    private final JdbcTemplate jdbc;
    private final ProcurementMailSender mailSender;
    private final AuditService auditService;

    public SupplierQuotationDeliveryService(JdbcTemplate jdbc,
                                            ProcurementMailSender mailSender,
                                            AuditService auditService) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.auditService = auditService;
    }

    public record Delivery(String status, String recipient, String errorMessage) {}

    @Transactional(readOnly = true)
    public Delivery latest(UUID quotationId) {
        BossContext context = requireContext();
        List<Delivery> rows = jdbc.query("""
                SELECT status, recipient, error_message
                FROM supplier_quotation_deliveries
                WHERE tenant_id = ? AND quotation_id = ?
                ORDER BY attempt_no DESC
                LIMIT 1
                """, (rs, rowNum) -> new Delivery(
                rs.getString("status"), rs.getString("recipient"), rs.getString("error_message")),
                context.tenantId(), quotationId);
        return rows.isEmpty() ? new Delivery(null, null, null) : rows.get(0);
    }

    @Transactional
    public Delivery send(UUID quotationId) {
        BossContext context = requireContext();
        UUID tenantId = context.tenantId();
        List<QuoteMail> quotes = jdbc.query("""
                SELECT q.id, q.sq_number, q.supplier_id, s.name, q.pr_id, p.pr_number
                FROM supplier_quotations q
                JOIN suppliers s ON s.id = q.supplier_id AND s.tenant_id = q.tenant_id
                JOIN purchase_requisitions p ON p.id = q.pr_id AND p.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.id = ?
                FOR UPDATE OF q
                """, (rs, rowNum) -> new QuoteMail(
                rs.getObject("id", UUID.class),
                rs.getString("sq_number"),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("name"),
                rs.getString("pr_number")), tenantId, quotationId);
        if (quotes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier quotation not found");
        }
        Integer sent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_quotation_deliveries
                WHERE tenant_id = ? AND quotation_id = ? AND status = 'sent'
                """, Integer.class, tenantId, quotationId);
        if (sent != null && sent > 0) {
            return latest(quotationId);
        }
        QuoteMail quote = quotes.get(0);
        String recipient = jdbc.query("""
                SELECT email FROM supplier_contacts
                WHERE tenant_id = ? AND supplier_id = ? AND email IS NOT NULL AND btrim(email) <> ''
                ORDER BY is_primary DESC, created_at
                LIMIT 1
                """, rs -> rs.next() ? rs.getString("email") : null, tenantId, quote.supplierId());
        Integer attempts = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_no), 0) FROM supplier_quotation_deliveries
                WHERE tenant_id = ? AND quotation_id = ?
                """, Integer.class, tenantId, quotationId);
        int attempt = (attempts == null ? 0 : attempts) + 1;
        String company = jdbc.query("SELECT name FROM tenant_registry WHERE id = ?",
                rs -> rs.next() ? rs.getString("name") : "BOSS", tenantId);
        ProcurementMailSender.SendResult result = recipient == null
                ? new ProcurementMailSender.SendResult(false, null, "The supplier has no email address")
                : mailSender.send(recipient, "Quotation request " + quote.number() + " from " + company,
                """
                <p>%s has requested a quotation for purchase requisition %s.</p>
                <p>Quotation reference: %s</p>
                <p>Please reply with your unit price, taxes, freight, discount, payment terms, and lead time.</p>
                """.formatted(escape(company), escape(quote.prNumber()), escape(quote.number())));
        jdbc.update("""
                INSERT INTO supplier_quotation_deliveries
                  (tenant_id, quotation_id, recipient, status, provider_message_id, error_message, attempt_no)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, quotationId, recipient, result.delivered() ? "sent" : "failed",
                result.providerMessageId(), result.errorMessage(), attempt);
        auditService.record("supplier_quotations", quotationId,
                result.delivered() ? "EMAIL_SENT" : "EMAIL_FAILED", null,
                "{\"attempt\":" + attempt + ",\"status\":\"" + (result.delivered() ? "sent" : "failed") + "\"}");
        return new Delivery(result.delivered() ? "sent" : "failed", recipient, result.errorMessage());
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is required");
        }
        return context;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record QuoteMail(UUID id, String number, UUID supplierId, String supplierName, String prNumber) {}
}
