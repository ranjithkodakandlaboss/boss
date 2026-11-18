package in.boss.identity.domain;

import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossRoles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompanyLocationService {
    private static final Set<String> SITE_TYPES = Set.of("factory", "warehouse", "office", "depot", "other");
    private static final Set<String> STORE_TYPES = Set.of(
            "raw_material", "finished_goods", "work_in_progress", "consumable", "tool", "rejected", "quarantine");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CompanyLocationService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> addSite(String name, String code, String siteType) {
        BossContext context = requireOwner();
        String siteName = required(name, "Site name and code are required");
        String siteCode = required(code, "Site name and code are required");
        String type = approved(siteType, "factory", SITE_TYPES, "Site type is not an approved value");
        UUID legalEntityId = legalEntity(context.tenantId());
        rejectExisting(count(context.tenantId(), siteCode), "A site with this code already exists");
        UUID id = jdbc.queryForObject(
                "INSERT INTO physical_sites(tenant_id, legal_entity_id, name, code, site_type) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, context.tenantId(), legalEntityId, siteName, siteCode, type);
        audit.record("physical_sites", id, "CREATE", null, "{\"code\":\"" + escape(siteCode) + "\"}");
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("id", id);
        site.put("name", siteName);
        site.put("code", siteCode);
        site.put("siteType", type);
        return site;
    }

    @Transactional
    public Map<String, Object> addStore(String siteCode, String name, String code, String storeType) {
        BossContext context = requireOwner();
        String location = required(siteCode, "Store name, code, and site are required");
        String storeName = required(name, "Store name, code, and site are required");
        String storeCode = required(code, "Store name, code, and site are required");
        String type = approved(storeType, "raw_material", STORE_TYPES, "Store type is not an approved value");
        UUID siteId = siteId(context.tenantId(), location);
        rejectExisting(storeCount(context.tenantId(), siteId, storeCode), "A store with this code already exists at the site");
        UUID id = jdbc.queryForObject(
                "INSERT INTO stores(tenant_id, site_id, name, code, store_type) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, context.tenantId(), siteId, storeName, storeCode, type);
        audit.record("stores", id, "CREATE", null, "{\"code\":\"" + escape(storeCode) + "\"}");
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("id", id);
        store.put("siteId", siteId);
        store.put("name", storeName);
        store.put("code", storeCode);
        store.put("storeType", type);
        store.put("siteCode", location);
        return store;
    }

    private UUID legalEntity(UUID tenantId) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM legal_entities WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Legal entity was not provisioned");
        return ids.get(0);
    }

    private Integer count(UUID tenantId, String code) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM physical_sites WHERE tenant_id = ? AND lower(code) = lower(?)",
                Integer.class, tenantId, code);
    }

    private UUID siteId(UUID tenantId, String siteCode) {
        List<UUID> sites = jdbc.query(
                "SELECT id FROM physical_sites WHERE tenant_id = ? AND lower(code) = lower(?)",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, siteCode);
        if (sites.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store site was not found");
        return sites.get(0);
    }

    private Integer storeCount(UUID tenantId, UUID siteId, String code) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stores WHERE tenant_id = ? AND site_id = ? AND lower(code) = lower(?)",
                Integer.class, tenantId, siteId, code);
    }

    private static void rejectExisting(Integer count, String message) {
        if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return value.trim();
    }

    private static String approved(String value, String fallback, Set<String> allowed, String message) {
        String chosen = value == null || value.isBlank() ? fallback : value.trim();
        if (!allowed.contains(chosen)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return chosen;
    }

    private BossContext requireOwner() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign-in is required");
        }
        if (!context.hasRole(BossRoles.OWNER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OWNER role is required");
        }
        return context;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
