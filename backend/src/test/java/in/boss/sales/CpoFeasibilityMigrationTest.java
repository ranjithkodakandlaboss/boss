package in.boss.sales;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class CpoFeasibilityMigrationTest {

    @Test
    void upgradesFromVersion100WithExistingAndFeasibilityStates() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:15-alpine")) {
            postgres.start();
            String url = postgres.getJdbcUrl();
            String user = postgres.getUsername();
            String password = postgres.getPassword();
            Flyway.configure().dataSource(url, user, password)
                    .locations("classpath:db/migration").target("100").load().migrate();
            var result = Flyway.configure().dataSource(url, user, password)
                    .locations("classpath:db/migration").load().migrate();
            assertThat(result.migrations).extracting(migration -> migration.version).containsExactly("102");

            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.prepareStatement("""
                         SELECT pg_get_constraintdef(c.oid)
                         FROM pg_constraint c
                         JOIN pg_namespace n ON n.oid = c.connamespace
                         WHERE n.nspname = ? AND c.conname = 'cpos_status_check'
                         """)) {
                statement.setString(1, "public");
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).contains("'open'", "'partial'", "'fulfilled'", "'cancelled'",
                            "'on_hold'", "'feasibility_pending'", "'feasibility_approved'");
                }
            }
        }
    }
}
