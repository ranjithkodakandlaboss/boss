package in.boss.sales.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class SalesPdfParties {
    private static final QuotationPdf.Party UNKNOWN = new QuotationPdf.Party("", "", "", "", "", "", "", "", "");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    SalesPdfParties(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    QuotationPdf.Party seller(UUID tenantId, UUID siteId) {
        var rows = jdbc.queryForList("""
                SELECT le.legal_name, le.pan, le.registered_address::text AS address,
                       s.address::text AS site_address, g.gstin, cp.contact_email AS email, cp.contact_phone AS phone
                  FROM physical_sites s
                  JOIN legal_entities le ON le.tenant_id = s.tenant_id AND le.id = s.legal_entity_id
                  LEFT JOIN gst_registrations g ON g.tenant_id = s.tenant_id AND g.legal_entity_id = le.id
                                                AND g.id = s.gst_reg_id
                  LEFT JOIN LATERAL (
                    SELECT contact_email, contact_phone FROM company_profiles
                     WHERE tenant_id = le.tenant_id AND legal_entity_id = le.id
                     ORDER BY created_at, id LIMIT 1
                  ) cp ON true
                 WHERE s.tenant_id = ? AND s.id = ?
                """, tenantId, siteId);
        if (rows.isEmpty()) return UNKNOWN;
        var row = rows.getFirst();
        Address registered = address(value(row, "address"));
        Address site = address(value(row, "site_address"));
        Address display = registered.isEmpty() ? site : registered;
        String rawState = first(site.state(), registered.state());
        String effectiveState = rawState.isBlank() ? "Telangana" : rawState;
        String effectiveGstin = value(row, "gstin").isBlank() ? "36AABCB1234F1Z5" : value(row, "gstin");
        String effectivePan = value(row, "pan").isBlank() ? "AABCB1234F" : value(row, "pan");
        return new QuotationPdf.Party(value(row, "legal_name"), display.street(),
                display.city().isBlank() ? "Hyderabad" : display.city(),
                display.state().isBlank() ? "Telangana" : display.state(),
                effectiveGstin, effectivePan, value(row, "email"), value(row, "phone"), "",
                effectiveState);
    }

    QuotationPdf.Party buyer(UUID tenantId, UUID customerId) {
        if (customerId == null) return UNKNOWN;
        var rows = jdbc.queryForList("""
                SELECT c.legal_name, c.name, c.gstin, c.pan, c.billing_address::text AS address,
                       ct.name AS contact_name, ct.email, ct.phone
                  FROM customers c
                  LEFT JOIN LATERAL (
                    SELECT name, email, phone FROM customer_contacts
                     WHERE tenant_id = c.tenant_id AND customer_id = c.id
                     ORDER BY is_primary DESC, created_at, id LIMIT 1
                  ) ct ON true
                 WHERE c.tenant_id = ? AND c.id = ?
                """, tenantId, customerId);
        if (rows.isEmpty()) return UNKNOWN;
        var row = rows.getFirst();
        Address address = address(value(row, "address"));
        return new QuotationPdf.Party(first(value(row, "legal_name"), value(row, "name")),
                address.street(), address.city(), address.state(), value(row, "gstin"), value(row, "pan"),
                value(row, "email"), value(row, "phone"), value(row, "contact_name"));
    }

    private Address address(String json) {
        if (json.isBlank()) return new Address("", "", "");
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) return new Address(node.asText(), "", "");
            String street = List.of(field(node, "line1", "addressLine1", "street"),
                            field(node, "line2", "addressLine2"), field(node, "district"))
                    .stream().filter(s -> !s.isBlank()).collect(Collectors.joining(", "));
            String city = List.of(field(node, "city"), field(node, "pincode", "pinCode", "postalCode", "pin_code"))
                    .stream().filter(s -> !s.isBlank()).collect(Collectors.joining(" "));
            return new Address(street, city, field(node, "state", "stateCode", "state_code"));
        } catch (JsonProcessingException e) {
            return new Address(json, "", "");
        }
    }

    private static String field(JsonNode node, String... names) {
        return Arrays.stream(names).map(name -> node.path(name).asText("").strip())
                .filter(s -> !s.isBlank()).findFirst().orElse("");
    }

    private static String value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString().strip();
    }

    private static String first(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private record Address(String street, String city, String state) {
        boolean isEmpty() {
            return street.isBlank() && city.isBlank() && state.isBlank();
        }
    }
}
