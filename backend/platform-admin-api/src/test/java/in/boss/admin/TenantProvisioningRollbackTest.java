package in.boss.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantProvisioningRollbackTest extends PlatformAdminIntegrationTest {
    @Test
    void failedIndustrySeedLeavesNoCompanyRecords() throws Exception {
        authenticateAdmin();
        String suffix = UUID.randomUUID().toString();
        JdbcTemplate privileged = privilegedJdbc();
        int groupsBefore = privileged.queryForObject("SELECT count(*) FROM customer_groups", Integer.class);
        privileged.execute("""
                CREATE OR REPLACE FUNCTION test_fail_industry_seed() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                  RAISE EXCEPTION 'forced seed failure';
                END $$
                """);
        privileged.execute("CREATE TRIGGER test_fail_industry_seed BEFORE INSERT ON tenant_config_options " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_industry_seed()");
        try {
            assertThatThrownBy(() -> mvc.perform(post("/admin/api/v1/tenants")
                            .header("Authorization", "Bearer admin-token")
                            .header("Idempotency-Key", "seed-fail-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("seed-group-" + suffix, "seed-tenant-" + suffix, "FORGING"))))
                    .isInstanceOf(Exception.class);
        } finally {
            privileged.execute("DROP TRIGGER test_fail_industry_seed ON tenant_config_options");
            privileged.execute("DROP FUNCTION test_fail_industry_seed()");
        }

        assertThat(privileged.queryForObject("SELECT count(*) FROM customer_groups WHERE slug = ?",
                Integer.class, "seed-group-" + suffix)).isZero();
        assertThat(privileged.queryForObject("SELECT count(*) FROM tenant_registry WHERE slug = ?",
                Integer.class, "seed-tenant-" + suffix)).isZero();
        assertThat(privileged.queryForObject("SELECT count(*) FROM customer_groups", Integer.class)).isEqualTo(groupsBefore);
    }
}
