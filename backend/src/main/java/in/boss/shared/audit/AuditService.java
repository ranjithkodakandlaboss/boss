package in.boss.shared.audit;

import in.boss.shared.context.BossContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes append-only audit_events rows.
 * Must be called within the same transaction as the business operation.
 * Never update or delete from audit_events — the DB trigger blocks it.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String entityType, UUID entityId, String action,
                       Object oldData, Object newData) {
        var ctx = BossContextHolder.get();
        if (ctx == null) {
            throw new IllegalStateException("AuditService requires BossContext");
        }

        jdbc.update("""
                INSERT INTO audit_events
                  (tenant_id, actor_id, entity_type, entity_id, action, old_data, new_data)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
                ctx.tenantId(),
                ctx.userId(),
                entityType,
                entityId,
                action,
                toJson(oldData),
                toJson(newData));
    }

    private String toJson(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        // Domain services pass pre-serialised JSON strings
        return value.toString();
    }
}
