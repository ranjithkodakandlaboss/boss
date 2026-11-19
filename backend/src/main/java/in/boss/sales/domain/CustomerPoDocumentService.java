package in.boss.sales.domain;

import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@PreAuthorize(BossPermissions.SALES_ACCESS)
public class CustomerPoDocumentService {
    private final CpoLookup lookup;
    private final JdbcTemplate jdbc;
    private final SalesPdfParties parties;

    CustomerPoDocumentService(CpoLookup lookup, JdbcTemplate jdbc, SalesPdfParties parties) {
        this.lookup = lookup;
        this.jdbc = jdbc;
        this.parties = parties;
    }

    public record Document(String filename, byte[] pdf) {}

    @Transactional(readOnly = true)
    public Document render(UUID cpoId) {
        var context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant user context is required");
        }
        UUID tenantId = context.tenantId();
        var sites = jdbc.query("SELECT site_id FROM cpos WHERE tenant_id = ? AND id = ?",
                (rs, row) -> rs.getObject("site_id", UUID.class), tenantId, cpoId);
        if (sites.isEmpty() || !context.canAccessSite(sites.getFirst())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer PO not found");
        }
        var order = lookup.read(tenantId, cpoId);
        return new Document(order.cpoNumber() + ".pdf", QuotationPdf.render(order,
                parties.seller(tenantId, sites.getFirst()), parties.buyer(tenantId, order.customerId())));
    }
}
