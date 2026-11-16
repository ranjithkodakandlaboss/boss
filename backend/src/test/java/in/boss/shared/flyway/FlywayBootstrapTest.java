package in.boss.shared.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance test for EPIC-01-S001 / GitHub Issue #11:
 * "Flyway runs all V001-V010 migrations on fresh DB without errors; schema_history table populated.
 *  Test case: Run flyway migrate from scratch; verify 10 entries in schema_history."
 */
class FlywayBootstrapTest {

    @Test
    @DisplayName("Run Flyway V001-V010 from scratch on a fresh database and verify 10 schema_history entries")
    void v001_to_v010_migrations_succeedOnFreshDatabase() throws Exception {
        String url = System.getProperty("test.datasource.url", "jdbc:postgresql://localhost:5432/test_flyway_v001_v010");
        String user = System.getProperty("test.datasource.username", "boss");
        String pass = System.getProperty("test.datasource.password", "boss");

        // Reset public schema to guarantee a completely fresh database state
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;");
        }

        // Configure Flyway to target version 010 (V001 through V010)
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target("010")
                .load();

        MigrateResult result = flyway.migrate();

        assertThat(result.success)
                .as("Flyway V001-V010 migration should succeed without errors")
                .isTrue();
        assertThat(result.migrationsExecuted)
                .as("Exactly 10 migrations should be executed (V001 to V010)")
                .isEqualTo(10);

        // Verify flyway_schema_history table entries
        List<String> versions = new ArrayList<>();
        List<Boolean> successFlags = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString("version"));
                successFlags.add(rs.getBoolean("success"));
            }
        }

        assertThat(versions)
                .as("Schema history must contain exactly 10 versions from 001 to 010")
                .containsExactly(
                        "001", "002", "003", "004", "005",
                        "006", "007", "008", "009", "010"
                );

        assertThat(successFlags)
                .as("All 10 migrations must have success = true")
                .hasSize(10)
                .allMatch(Boolean::booleanValue);

        // Verify core bootstrap tables created by V001-V010 exist
        List<String> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString("table_name"));
            }
        }

        assertThat(tables).contains(
                "tenant_registry",
                "tenant_entitlements",
                "audit_events",
                "outbox_events",
                "idempotency_keys",
                "app_users",
                "user_roles",
                "legal_entities",
                "gst_registrations",
                "physical_sites",
                "stores",
                "units_of_measure",
                "hsn_codes"
        );
    }
}
