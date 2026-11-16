package in.boss.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantStatusCacheTest {
    @Test
    void observesSuspensionOnTheNextRequest() {
        UUID tenantId = UUID.randomUUID();
        JdbcTemplate jdbc = new StubStatusJdbcTemplate("active", "suspended");
        TenantStatusCache cache = new TenantStatusCache(jdbc);

        assertThat(cache.getStatus(tenantId)).isEqualTo("active");
        assertThat(cache.getStatus(tenantId)).isEqualTo("suspended");
    }

    static final class StubStatusJdbcTemplate extends JdbcTemplate {
        private final Deque<String> statuses = new ArrayDeque<>();

        StubStatusJdbcTemplate(String... initialStatuses) {
            statuses.addAll(java.util.List.of(initialStatuses));
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(statuses.removeFirst());
        }
    }
}
