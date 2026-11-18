package in.boss.shared.config;

import in.boss.shared.context.BossContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

final class ConfigOptionHistoryService {

    private ConfigOptionHistoryService() {
    }

    static void record(JdbcTemplate jdbc, UUID optionId, BossContext context, String changeType,
                       String oldLabel, String newLabel) {
        jdbc.update("""
                INSERT INTO tenant_config_option_history
                  (option_id, tenant_id, changed_by, old_label, new_label, change_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """, optionId, context.tenantId(), context.userId(), oldLabel, newLabel, changeType);
    }
}
