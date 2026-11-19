package in.boss.documents.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

@Component
public class DocumentLinkValidator {
    private final JdbcTemplate jdbc;

    public DocumentLinkValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(UUID tenantId, String type, UUID id) {
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        String salesTable = switch (normalized) {
            case "rfq" -> "rfqs";
            case "quotation" -> "quotations";
            case "customerpo", "customer_po", "customer po", "cpo" -> "cpos";
            default -> null;
        };
        if (salesTable != null) {
            validateSalesRecord(tenantId, type, id, salesTable);
        } else if ("customer".equals(normalized)) {
            validateCustomer(tenantId, id);
        } else {
            validateOtherRecord(tenantId, type, id, normalized);
        }
    }

    private void validateSalesRecord(UUID tenantId, String type, UUID id, String table) {
        requireId(type, id);
        Boolean active = jdbc.query("SELECT c.is_active FROM customers c JOIN " + table
                        + " s ON s.tenant_id = c.tenant_id AND s.customer_id = c.id"
                        + " WHERE s.id = ? AND s.tenant_id = ? FOR UPDATE OF c",
                rs -> rs.next() ? rs.getBoolean("is_active") : null, id, tenantId);
        if (Boolean.FALSE.equals(active)) {
            throw new DocumentValidationException("New documents cannot be linked to an inactive customer's records");
        }
        String status = jdbc.query("SELECT status FROM " + table
                        + " WHERE id = ? AND tenant_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString("status") : null, id, tenantId);
        if (status == null) {
            throw new DocumentValidationException("Linked " + type + " record not found for this tenant: " + id);
        }
        if ("cancelled".equals(status)) {
            throw new DocumentValidationException("New documents cannot be linked to a cancelled " + type);
        }
    }

    private void validateCustomer(UUID tenantId, UUID id) {
        requireId("Customer", id);
        Boolean active = jdbc.query("SELECT is_active FROM customers WHERE tenant_id = ? AND id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getBoolean("is_active") : null, tenantId, id);
        if (active == null) {
            throw new DocumentValidationException("Linked Customer record not found for this tenant: " + id);
        }
        if (!active) {
            throw new DocumentValidationException("New documents cannot be linked to an inactive customer");
        }
    }

    private void validateOtherRecord(UUID tenantId, String type, UUID id, String normalized) {
        if (id == null) return;
        String table = switch (normalized) {
            case "supplier" -> "suppliers";
            case "job" -> "production_jobs";
            case "asset" -> "assets";
            default -> null;
        };
        if (table != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE id = ? AND tenant_id = ?",
                    Integer.class, id, tenantId);
            if (count == null || count == 0) {
                throw new DocumentValidationException("Linked " + type + " record not found for this tenant: " + id);
            }
        }
    }

    private static void requireId(String type, UUID id) {
        if (id == null) {
            throw new DocumentValidationException("A linked " + type + " ID is required");
        }
    }
}
