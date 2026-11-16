package in.boss.admin;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

final class PlatformAdminPostgres {
    static final String ADMIN_UID = "test-platform-admin";
    static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String ADMIN_PASSWORD = "test-only-password";
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15.10-alpine")
            .withDatabaseName("boss_admin_test")
            .withUsername("postgres")
            .withPassword("postgres");

    private static boolean ready;

    private PlatformAdminPostgres() { }

    static synchronized void ensure() {
        if (ready) return;
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE ROLE boss_admin LOGIN PASSWORD '" + ADMIN_PASSWORD + "' NOSUPERUSER NOBYPASSRLS INHERIT");
            executeScript(sql, script());
            sql.execute("INSERT INTO platform_admin_users(id, firebase_uid, email) VALUES ('"
                    + ADMIN_ID + "', '" + ADMIN_UID + "', 'platform-admin@boss.test')");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
        ready = true;
    }

    private static String script() throws Exception {
        try (var input = PlatformAdminPostgres.class.getResourceAsStream("/db/boss-admin-grants.sql")) {
            if (input == null) throw new IllegalStateException("boss-admin-grants.sql is not on the classpath");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeScript(Statement sql, String script) throws Exception {
        StringBuilder statement = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            statement.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                sql.execute(statement.toString());
                statement.setLength(0);
            }
        }
    }
}
