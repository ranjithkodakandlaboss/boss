package in.boss.identity.domain;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import in.boss.identity.api.ActivateRequest;
import in.boss.identity.api.InviteRequestBody;
import in.boss.identity.api.UserResponse;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import in.boss.shared.security.BossRoles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerIdentityService {
    private static final int TTL_DAYS = 7;
    private static final Set<String> INVITE_ROLES = Set.of(
            BossRoles.OPS_MANAGER, BossRoles.SALES_MANAGER, BossRoles.PURCHASE_MANAGER,
            BossRoles.PRODUCTION_SUPERVISOR, BossRoles.STORE_KEEPER, BossRoles.ACCOUNTS_STAFF,
            BossRoles.QUALITY_INSPECTOR, BossRoles.FIELD_WORKER);

    private final JdbcTemplate jdbc;
    private final FirebaseAuth firebaseAuth;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public CustomerIdentityService(JdbcTemplate jdbc, FirebaseAuth firebaseAuth, AuditService audit,
                                   PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.firebaseAuth = firebaseAuth;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactions);
    }

    public UserResponse activate(ActivateRequest request) {
        if (request.token() == null || request.token().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activation token is required");
        }
        if (request.password() == null || request.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
        InviteRow invite = lookup(sha256(request.token().trim()));
        if (invite == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Activation token was not found");
        if (!"pending".equals(invite.status()) || invite.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Activation token is no longer pending");
        }
        if (!BossRoles.TENANT_ROLES.contains(invite.role())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation role is not a tenant role");
        }
        String uid = createFirebaseUser(invite.email(), request.password(), request.displayName());
        BossContextHolder.set(new BossContext(invite.tenantId(), null, Set.of(invite.role()), null, uid));
        try {
            return transactions.execute(status -> {
                String tenantStatus = jdbc.queryForObject(
                        "SELECT status FROM tenant_registry WHERE id = ?", String.class, invite.tenantId());
                if (!"active".equals(tenantStatus)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant is not active");
                }
                return accept(invite, uid, request.displayName());
            });
        } catch (RuntimeException exception) {
            deleteFirebaseUser(uid);
            throw exception;
        } finally {
            BossContextHolder.clear();
        }
    }

    public UserResponse currentUser() {
        BossContext context = requireContext();
        return loadUser(context.userId());
    }

    public Map<String, Object> tenantInfo() {
        BossContext context = requireContext();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, slug, status, plan_tier, customer_group_id FROM tenant_registry WHERE id = ?",
                context.tenantId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found");
        Map<String, Object> row = rows.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("name", row.get("name"));
        body.put("slug", row.get("slug"));
        body.put("status", row.get("status"));
        body.put("planTier", row.get("plan_tier"));
        body.put("customerGroupId", row.get("customer_group_id"));
        return body;
    }

    public Map<String, Object> createInvite(InviteRequestBody request) {
        BossContext context = requireOwner();
        if (request.email() == null || !request.email().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
        }
        if (!INVITE_ROLES.contains(request.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role cannot be invited");
        }
        String email = request.email().trim();
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM app_users WHERE tenant_id = ? AND lower(email) = lower(?)",
                Integer.class, context.tenantId(), email);
        if (existing != null && existing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User with this email is already active in the tenant");
        }
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT id, email, role, status, expires_at, created_at FROM invite_tokens "
                        + "WHERE tenant_id = ? AND lower(email) = lower(?) AND role = ? AND status = 'pending' "
                        + "AND expires_at > now()",
                context.tenantId(), email, request.role());
        if (!pending.isEmpty()) return inviteBody(pending.get(0));
        String token = newToken();
        Instant expiresAt = Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS);
        UUID id = jdbc.queryForObject(
                "INSERT INTO invite_tokens(tenant_id, email, role, token_hash, invited_by, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'pending', ?) RETURNING id",
                UUID.class, context.tenantId(), email, request.role(), sha256(token), context.userId(),
                Timestamp.from(expiresAt));
        audit.record("invite_tokens", id, "invite.created", null,
                "{\"email\":\"" + json(email) + "\",\"role\":\"" + request.role() + "\"}");
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", id);
        created.put("email", email);
        created.put("role", request.role());
        created.put("status", "pending");
        created.put("expiresAt", expiresAt);
        created.put("createdAt", Instant.now());
        return created;
    }

    public List<Map<String, Object>> listInvites() {
        requireOwner();
        return jdbc.queryForList(
                "SELECT id, email, role, status, expires_at, created_at FROM invite_tokens "
                        + "WHERE status = 'pending' ORDER BY created_at").stream().map(this::inviteBody).toList();
    }

    public void cancelInvite(UUID inviteId) {
        BossContext context = requireOwner();
        int updated = jdbc.update(
                "UPDATE invite_tokens SET status = 'cancelled' WHERE id = ? AND tenant_id = ? AND status = 'pending'",
                inviteId, context.tenantId());
        if (updated == 0) {
            Integer found = jdbc.queryForObject(
                    "SELECT count(*) FROM invite_tokens WHERE id = ? AND tenant_id = ?",
                    Integer.class, inviteId, context.tenantId());
            if (found == null || found == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite was not found");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invite is already accepted or expired");
        }
        audit.record("invite_tokens", inviteId, "invite.cancelled", null, "{}");
    }

    public Map<String, Object> listUsers() {
        requireOwner();
        List<UserResponse> users = jdbc.query(
                "SELECT id FROM app_users ORDER BY created_at",
                (rs, row) -> loadUser(rs.getObject("id", UUID.class)));
        return Map.of("content", users, "page", 0, "size", users.size(), "totalElements", users.size(), "totalPages", 1);
    }

    private UserResponse accept(InviteRow invite, String uid, String displayName) {
        UUID userId = jdbc.queryForObject(
                "INSERT INTO app_users(tenant_id, firebase_uid, email, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'active') RETURNING id",
                UUID.class, invite.tenantId(), uid, invite.email(), blankToNull(displayName));
        jdbc.update("INSERT INTO user_roles(tenant_id, user_id, role) VALUES (?, ?, ?)",
                invite.tenantId(), userId, invite.role());
        int updated = jdbc.update(
                "UPDATE invite_tokens SET status = 'accepted' WHERE id = ? AND status = 'pending'",
                invite.inviteId());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Activation token is no longer pending");
        BossContextHolder.set(new BossContext(invite.tenantId(), userId, Set.of(invite.role()), null, uid));
        audit.record("app_users", userId, "user.activated", null,
                "{\"email\":\"" + json(invite.email()) + "\",\"role\":\"" + invite.role() + "\"}");
        return loadUser(userId);
    }

    private UserResponse loadUser(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_id, firebase_uid, email, display_name, phone_wa, status, created_at "
                        + "FROM app_users WHERE id = ?", userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found");
        Map<String, Object> row = rows.get(0);
        List<UserResponse.RoleMembership> roles = jdbc.query(
                "SELECT role, site_id, granted_at FROM user_roles WHERE user_id = ?",
                (rs, index) -> new UserResponse.RoleMembership(
                        rs.getString("role"), rs.getObject("site_id", UUID.class),
                        rs.getTimestamp("granted_at").toInstant()),
                userId);
        return new UserResponse(
                (UUID) row.get("id"), (UUID) row.get("tenant_id"), (String) row.get("firebase_uid"),
                (String) row.get("email"), (String) row.get("display_name"), (String) row.get("phone_wa"),
                (String) row.get("status"), roles, ((Timestamp) row.get("created_at")).toInstant());
    }

    private InviteRow lookup(String hash) {
        List<InviteRow> rows = jdbc.query(
                "SELECT out_invite_id, out_tenant_id, out_email, out_role, out_status, out_expires_at "
                        + "FROM boss_lookup_invite_by_hash(?)",
                (rs, index) -> new InviteRow(
                        rs.getObject("out_invite_id", UUID.class),
                        rs.getObject("out_tenant_id", UUID.class),
                        rs.getString("out_email"), rs.getString("out_role"), rs.getString("out_status"),
                        rs.getTimestamp("out_expires_at").toInstant()),
                hash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String createFirebaseUser(String email, String password, String displayName) {
        try {
            UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setPassword(password)
                    .setEmailVerified(false);
            if (displayName != null && !displayName.isBlank()) request.setDisplayName(displayName.trim());
            return firebaseAuth.createUser(request).getUid();
        } catch (FirebaseAuthException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Firebase could not create this user");
        }
    }

    private void deleteFirebaseUser(String uid) {
        try {
            firebaseAuth.deleteUser(uid);
        } catch (FirebaseAuthException ignored) {
            // The database transaction is already rolling back. The Firebase user is left for an operator.
        }
    }

    private BossContext requireContext() {
        BossContext context = BossContextHolder.get();
        if (context == null || context.tenantId() == null || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign-in is required");
        }
        return context;
    }

    private BossContext requireOwner() {
        BossContext context = requireContext();
        if (!context.hasRole(BossRoles.OWNER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OWNER role is required");
        }
        return context;
    }

    private Map<String, Object> inviteBody(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("email", row.get("email"));
        body.put("role", row.get("role"));
        body.put("status", row.get("status"));
        body.put("expiresAt", row.get("expires_at"));
        body.put("createdAt", row.get("created_at"));
        return body;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash invite token", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record InviteRow(UUID inviteId, UUID tenantId, String email, String role, String status, Instant expiresAt) { }
}
