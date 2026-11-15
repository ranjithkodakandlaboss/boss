package in.boss.shared.rls;

import in.boss.shared.context.BossContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Runs SET LOCAL app.current_tenant_id = ? immediately after a connection is
 * borrowed from HikariCP. If BossContext is not set the parameter is set to
 * NULL — PostgreSQL RLS then returns zero rows (secure default).
 */
public final class BossConnectionWrapper {

    private static final Logger log = LoggerFactory.getLogger(BossConnectionWrapper.class);
    private static final String SET_TENANT =
            "SELECT set_config('app.current_tenant_id', ?, true)";

    private BossConnectionWrapper() {}

    public static void applyTenantContext(Connection conn) throws SQLException {
        var ctx = BossContextHolder.get();
        String tenantId = ctx != null && ctx.tenantId() != null
                ? ctx.tenantId().toString()
                : "";

        try (PreparedStatement ps = conn.prepareStatement(SET_TENANT)) {
            ps.setString(1, tenantId);
            ps.execute();
        }

        if (ctx == null) {
            log.debug("No BossContext — tenant context cleared on connection");
        }
    }
}
