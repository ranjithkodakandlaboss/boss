package in.boss.identity.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.boss.identity.api.CompanySaveRequest;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossRoles;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompanyWorkspaceService {
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Set<String> ENTITY_TYPES = Set.of(
            "PRIVATE_LIMITED", "LLP", "SOLE_PROPRIETORSHIP", "PARTNERSHIP", "PUBLIC_LIMITED", "HUF", "OTHER");
    private static final Set<String> SITE_TYPES = Set.of("factory", "warehouse", "office", "depot", "other");
    private static final Set<String> STORE_TYPES = Set.of(
            "raw_material", "finished_goods", "work_in_progress", "consumable", "tool", "rejected", "quarantine");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public CompanyWorkspaceService(JdbcTemplate jdbc, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = mapper;
    }

    public Map<String, Object> company() {
        requireUser();
        return readCompany();
    }

    public Map<String, Object> onboarding() {
        BossContext context = requireUser();
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT industry_type, onboarding_completed_at FROM tenant_onboarding_config WHERE tenant_id = ?",
                context.tenantId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant onboarding configuration is missing");
        body.put("industryType", rows.get(0).get("industry_type"));
        body.put("completed", rows.get(0).get("onboarding_completed_at") != null);
        body.put("completedAt", rows.get(0).get("onboarding_completed_at"));
        body.put("company", readCompany());
        return body;
    }

    @Transactional
    public Map<String, Object> save(CompanySaveRequest request, boolean complete) {
        BossContext context = requireOwner();
        validate(request, complete);
        UUID legalEntityId = jdbc.queryForObject(
                "SELECT id FROM legal_entities WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                UUID.class, context.tenantId());
        if (legalEntityId == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Legal entity was not provisioned");
        String address = addressJson(request);
        jdbc.update("UPDATE tenant_registry SET name = ?, updated_at = now() WHERE id = ?",
                request.companyName().trim(), context.tenantId());
        jdbc.update("UPDATE legal_entities SET name = ?, legal_name = ?, pan = ?, entity_type = ?, "
                        + "registered_address = ?::jsonb, updated_at = now() WHERE id = ?",
                blank(request.tradingName(), request.companyName()), request.legalName().trim(),
                blankToNull(request.pan()), blankToNull(request.entityType()), address, legalEntityId);
        saveGst(context.tenantId(), legalEntityId, request);
        saveContact(context.tenantId(), legalEntityId, request);
        if (request.sites() != null) {
            for (CompanySaveRequest.SiteInput site : request.sites()) saveSite(context.tenantId(), legalEntityId, site);
        }
        if (request.stores() != null) {
            for (CompanySaveRequest.StoreInput store : request.stores()) saveStore(context.tenantId(), store);
        }
        if (complete) {
            jdbc.update("UPDATE tenant_onboarding_config SET onboarding_completed_at = now(), onboarding_completed_by = ? "
                            + "WHERE tenant_id = ? AND onboarding_completed_at IS NULL",
                    context.userId(), context.tenantId());
        }
        audit.record("legal_entities", legalEntityId, complete ? "onboarding.completed" : "company.updated",
                null, "{\"companyName\":\"" + json(request.companyName()) + "\"}");
        return readCompany();
    }

    private void validate(CompanySaveRequest request, boolean complete) {
        if (request.companyName() == null || request.companyName().isBlank()
                || request.legalName() == null || request.legalName().isBlank()
                || request.city() == null || request.city().isBlank()
                || request.state() == null || request.state().isBlank()
                || request.email() == null || !request.email().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Company name, legal name, city, state, and email are required");
        }
        if (request.entityType() != null && !request.entityType().isBlank() && !ENTITY_TYPES.contains(request.entityType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entity type is not an approved value");
        }
        if (request.pan() != null && !request.pan().isBlank() && !PAN.matcher(request.pan().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PAN format is invalid");
        }
        boolean gstSkipped = Boolean.TRUE.equals(request.gstNotApplicable());
        if (!gstSkipped && request.gstin() != null && !request.gstin().isBlank()
                && !GSTIN.matcher(request.gstin().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GSTIN format is invalid");
        }
        if (request.sites() != null) {
            for (CompanySaveRequest.SiteInput site : request.sites()) {
                boolean named = site.name() != null && !site.name().isBlank();
                boolean coded = site.code() != null && !site.code().isBlank();
                if (named != coded) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A site needs both a name and a code");
                }
            }
        }
        if (complete) {
            boolean namedSite = request.sites() != null && request.sites().stream()
                    .anyMatch(site -> site.name() != null && !site.name().isBlank()
                            && site.code() != null && !site.code().isBlank());
            if (!namedSite) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A site name and code are required");
        }
    }

    private void saveGst(UUID tenantId, UUID legalEntityId, CompanySaveRequest request) {
        if (Boolean.TRUE.equals(request.gstNotApplicable()) || request.gstin() == null || request.gstin().isBlank()) {
            jdbc.update("UPDATE gst_registrations SET status = 'cancelled', updated_at = now() "
                    + "WHERE tenant_id = ? AND status = 'active'", tenantId);
            return;
        }
        String gstin = request.gstin().trim();
        List<UUID> existing = jdbc.query(
                "SELECT id FROM gst_registrations WHERE tenant_id = ? AND gstin = ?",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, gstin);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO gst_registrations(tenant_id, legal_entity_id, gstin, trade_name, effective_from) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    tenantId, legalEntityId, gstin, blankToNull(request.tradingName()), LocalDate.now());
        } else {
            jdbc.update("UPDATE gst_registrations SET trade_name = ?, status = 'active', updated_at = now() WHERE id = ?",
                    blankToNull(request.tradingName()), existing.get(0));
        }
    }

    private void saveContact(UUID tenantId, UUID legalEntityId, CompanySaveRequest request) {
        List<UUID> profiles = jdbc.query(
                "SELECT id FROM company_profiles WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId);
        if (profiles.isEmpty()) {
            jdbc.update("INSERT INTO company_profiles(tenant_id, legal_entity_id, display_name, contact_email, contact_phone, status) "
                            + "VALUES (?, ?, ?, ?, ?, 'draft')",
                    tenantId, legalEntityId, request.companyName().trim(), request.email().trim(), blankToNull(request.phone()));
        } else {
            jdbc.update("UPDATE company_profiles SET display_name = ?, contact_email = ?, contact_phone = ?, updated_at = now() WHERE id = ?",
                    request.companyName().trim(), request.email().trim(), blankToNull(request.phone()), profiles.get(0));
        }
    }

    private void saveSite(UUID tenantId, UUID legalEntityId, CompanySaveRequest.SiteInput site) {
        if (site.name() == null || site.name().isBlank() || site.code() == null || site.code().isBlank()) return;
        String siteType = site.siteType() == null || site.siteType().isBlank() ? "factory" : site.siteType();
        if (!SITE_TYPES.contains(siteType)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site type is not an approved value");
        String address = addressJson(site.addressLine(), site.city(), site.state());
        if (site.id() != null) {
            int updated = jdbc.update("UPDATE physical_sites SET name = ?, code = ?, site_type = ?, address = ?::jsonb, "
                            + "updated_at = now() WHERE id = ? AND tenant_id = ?",
                    site.name().trim(), site.code().trim(), siteType, address, site.id(), tenantId);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Site was not found in this tenant");
            return;
        }
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM physical_sites WHERE tenant_id = ? AND code = ?",
                Integer.class, tenantId, site.code().trim());
        if (existing != null && existing > 0) {
            jdbc.update("UPDATE physical_sites SET name = ?, site_type = ?, address = ?::jsonb, updated_at = now() "
                            + "WHERE tenant_id = ? AND code = ?",
                    site.name().trim(), siteType, address, tenantId, site.code().trim());
            return;
        }
        jdbc.update("INSERT INTO physical_sites(tenant_id, legal_entity_id, name, code, site_type, address) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                tenantId, legalEntityId, site.name().trim(), site.code().trim(), siteType, address);
    }

    private void saveStore(UUID tenantId, CompanySaveRequest.StoreInput store) {
        if (store.name() == null || store.name().isBlank() || store.code() == null || store.code().isBlank()
                || store.siteCode() == null || store.siteCode().isBlank()) return;
        String storeType = store.storeType() == null || store.storeType().isBlank() ? "raw_material" : store.storeType();
        if (!STORE_TYPES.contains(storeType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store type is not an approved value");
        }
        List<UUID> sites = jdbc.query(
                "SELECT id FROM physical_sites WHERE tenant_id = ? AND code = ?",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, store.siteCode().trim());
        if (sites.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store site was not found");
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE tenant_id = ? AND site_id = ? AND code = ?",
                Integer.class, tenantId, sites.get(0), store.code().trim());
        if (existing != null && existing > 0) {
            jdbc.update("UPDATE stores SET name = ?, store_type = ? WHERE tenant_id = ? AND site_id = ? AND code = ?",
                    store.name().trim(), storeType, tenantId, sites.get(0), store.code().trim());
            return;
        }
        jdbc.update("INSERT INTO stores(tenant_id, site_id, name, code, store_type) VALUES (?, ?, ?, ?, ?)",
                tenantId, sites.get(0), store.name().trim(), store.code().trim(), storeType);
    }

    private Map<String, Object> readCompany() {
        BossContext context = requireUser();
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT name FROM tenant_registry WHERE id = ?", context.tenantId());
        List<Map<String, Object>> entities = jdbc.queryForList(
                "SELECT id, name, legal_name, pan, entity_type, registered_address::text AS address "
                        + "FROM legal_entities WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                context.tenantId());
        if (entities.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Legal entity was not provisioned");
        Map<String, Object> entity = entities.get(0);
        List<Map<String, Object>> gst = jdbc.queryForList(
                "SELECT gstin, trade_name FROM gst_registrations WHERE tenant_id = ? AND status = 'active' ORDER BY created_at LIMIT 1",
                context.tenantId());
        List<Map<String, Object>> profiles = jdbc.queryForList(
                "SELECT contact_email, contact_phone FROM company_profiles WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                context.tenantId());
        Map<String, Object> address = parseAddress((String) entity.get("address"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", tenants.isEmpty() ? entity.get("name") : tenants.get(0).get("name"));
        body.put("tradingName", entity.get("name"));
        body.put("legalName", entity.get("legal_name"));
        body.put("entityType", entity.get("entity_type"));
        body.put("pan", entity.get("pan"));
        body.put("gstin", gst.isEmpty() ? null : gst.get(0).get("gstin"));
        body.put("addressLine", address.get("line1"));
        body.put("city", address.get("city"));
        body.put("state", address.get("state"));
        body.put("email", profiles.isEmpty() ? null : profiles.get(0).get("contact_email"));
        body.put("phone", profiles.isEmpty() ? null : profiles.get(0).get("contact_phone"));
        body.put("financialYear", null);
        body.put("currency", null);
        body.put("sites", sites(context.tenantId()));
        body.put("stores", stores(context.tenantId()));
        return body;
    }

    private List<Map<String, Object>> sites(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, code, site_type, address::text AS address FROM physical_sites WHERE tenant_id = ? ORDER BY created_at",
                tenantId);
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> address = parseAddress((String) row.get("address"));
            Map<String, Object> site = new LinkedHashMap<>();
            site.put("id", row.get("id"));
            site.put("name", row.get("name"));
            site.put("code", row.get("code"));
            site.put("siteType", row.get("site_type"));
            site.put("city", address.get("city"));
            site.put("state", address.get("state"));
            site.put("addressLine", address.get("line1"));
            sites.add(site);
        }
        return sites;
    }

    private List<Map<String, Object>> stores(UUID tenantId) {
        return jdbc.query(
                "SELECT s.id, s.site_id, s.name, s.code, s.store_type, p.code AS site_code FROM stores s "
                        + "JOIN physical_sites p ON p.id = s.site_id WHERE s.tenant_id = ? ORDER BY s.created_at",
                (rs, row) -> {
                    Map<String, Object> store = new LinkedHashMap<>();
                    store.put("id", rs.getObject("id", UUID.class));
                    store.put("siteId", rs.getObject("site_id", UUID.class));
                    store.put("name", rs.getString("name"));
                    store.put("code", rs.getString("code"));
                    store.put("storeType", rs.getString("store_type"));
                    store.put("siteCode", rs.getString("site_code"));
                    return store;
                }, tenantId);
    }

    private String addressJson(CompanySaveRequest request) {
        return addressJson(request.addressLine(), request.city(), request.state());
    }

    private String addressJson(String line1, String city, String state) {
        try {
            Map<String, String> address = new LinkedHashMap<>();
            address.put("line1", line1 == null ? "" : line1.trim());
            address.put("city", city == null ? "" : city.trim());
            address.put("state", state == null ? "" : state.trim());
            return mapper.writeValueAsString(address);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not store the address", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAddress(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private BossContext requireUser() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign-in is required");
        }
        return context;
    }

    private BossContext requireOwner() {
        BossContext context = requireUser();
        if (!context.hasRole(BossRoles.OWNER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OWNER role is required");
        }
        return context;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback.trim() : preferred.trim();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
