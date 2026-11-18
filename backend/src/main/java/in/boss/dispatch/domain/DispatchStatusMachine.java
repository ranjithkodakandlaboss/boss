package in.boss.dispatch.domain;

import in.boss.dispatch.api.DispatchDtos.DispatchLineDto;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dispatch lifecycle table and the status write that follows an allowed change.
 * The transition table is data so the service does not carry a decision per status.
 */
public final class DispatchStatusMachine {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "draft", Set.of("pending_docs", "dispatched", "cancelled"),
            "pending", Set.of("dispatched", "cancelled"),
            "pending_docs", Set.of("dispatched", "cancelled"),
            "packed", Set.of("dispatched", "cancelled"),
            "dispatched", Set.of("in_transit", "delivered", "delayed"),
            "in_transit", Set.of("delivered", "delayed"),
            "delayed", Set.of("in_transit", "delivered")
    );

    private DispatchStatusMachine() {
    }

    public static boolean allows(String from, String to) {
        Set<String> next = from == null ? null : ALLOWED.get(from);
        return next != null && next.contains(to);
    }

    public static void apply(
            JdbcTemplate jdbc,
            UUID tenantId,
            UUID dispatchId,
            String nextStatus,
            List<DispatchLineDto> lines) {
        if ("dispatched".equals(nextStatus)) {
            jdbc.update("""
                    UPDATE dispatches
                    SET status = ?, dispatched_at = NOW(), updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, nextStatus, tenantId, dispatchId);
            for (var line : lines) {
                if (line.cpoLineId() != null) {
                    jdbc.update("""
                            UPDATE cpo_lines
                            SET delivered_qty = delivered_qty + ?
                            WHERE tenant_id = ? AND id = ?
                            """, line.quantity(), tenantId, line.cpoLineId());
                }
            }
            return;
        }
        if ("delivered".equals(nextStatus)) {
            jdbc.update("""
                    UPDATE dispatches
                    SET status = ?, actual_delivery_date = COALESCE(actual_delivery_date, CURRENT_DATE), updated_at = NOW()
                    WHERE tenant_id = ? AND id = ?
                    """, nextStatus, tenantId, dispatchId);
            return;
        }
        jdbc.update("""
                UPDATE dispatches
                SET status = ?, updated_at = NOW()
                WHERE tenant_id = ? AND id = ?
                """, nextStatus, tenantId, dispatchId);
    }
}
