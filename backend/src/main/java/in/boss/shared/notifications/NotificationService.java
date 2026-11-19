package in.boss.shared.notifications;

import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static in.boss.shared.security.BossPermissions.ANY_TENANT_USER;

@Service
@Transactional
public class NotificationService {

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize(ANY_TENANT_USER)
    @Transactional(readOnly = true)
    public List<NotificationDtos.NotificationResponse> listForCurrentUser() {
        BossContext ctx = context();
        return jdbc.query("""
                SELECT id, notification_type, entity_type, entity_id, title, body, is_read, created_at
                FROM notifications
                WHERE tenant_id = ? AND user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 50
                """, this::map, ctx.tenantId(), ctx.userId());
    }

    @PreAuthorize(ANY_TENANT_USER)
    public NotificationDtos.NotificationResponse markRead(UUID id) {
        BossContext ctx = context();
        List<NotificationDtos.NotificationResponse> rows = jdbc.query("""
                UPDATE notifications
                SET is_read = TRUE, read_at = COALESCE(read_at, NOW())
                WHERE tenant_id = ? AND user_id = ? AND id = ?
                RETURNING id, notification_type, entity_type, entity_id, title, body, is_read, created_at
                """, this::map, ctx.tenantId(), ctx.userId(), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        return rows.get(0);
    }

    private NotificationDtos.NotificationResponse map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new NotificationDtos.NotificationResponse(
                rs.getObject("id", UUID.class), rs.getString("notification_type"),
                rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                rs.getString("title"), rs.getString("body"), rs.getBoolean("is_read"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static BossContext context() {
        BossContext ctx = BossContextHolder.get();
        if (ctx == null || ctx.tenantId() == null || ctx.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant context is required");
        }
        return ctx;
    }
}
