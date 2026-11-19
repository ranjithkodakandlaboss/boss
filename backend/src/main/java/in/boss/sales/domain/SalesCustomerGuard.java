package in.boss.sales.domain;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class SalesCustomerGuard {
    private final JdbcTemplate jdbc;

    public SalesCustomerGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireActive(UUID tenantId, UUID customerId) {
        Boolean active = jdbc.query("SELECT is_active FROM customers WHERE tenant_id = ? AND id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getBoolean("is_active") : null, tenantId, customerId);
        if (active == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active customer not found");
        }
        if (!active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inactive customers are read-only");
        }
    }

    public void requireActiveForRecord(UUID tenantId, String table, UUID recordId) {
        UUID customerId = jdbc.query("SELECT customer_id FROM " + table + " WHERE tenant_id = ? AND id = ?",
                rs -> rs.next() ? rs.getObject("customer_id", UUID.class) : null, tenantId, recordId);
        if (customerId != null) {
            requireActive(tenantId, customerId);
        }
    }
}
