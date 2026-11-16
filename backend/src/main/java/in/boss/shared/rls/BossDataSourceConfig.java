package in.boss.shared.rls;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps HikariCP so every borrowed connection immediately runs
 * SET app.current_tenant_id from BossContextHolder.
 *
 * Uses HikariCP's connectionInitSql for session-level defaults and a
 * custom proxy for the per-request SET LOCAL.
 */
@Configuration
public class BossDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.determineUrl());
        config.setUsername(props.determineUsername());
        config.setPassword(props.determinePassword());
        config.setDriverClassName(props.determineDriverClassName());

        return new BossRlsDataSource(new HikariDataSource(config));
    }

    /**
     * DataSource decorator that injects the RLS tenant parameter on every
     * getConnection() call.
     */
    static class BossRlsDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {

        BossRlsDataSource(HikariDataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = super.getConnection();
            BossConnectionWrapper.applyTenantContext(conn);
            return conn;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection conn = super.getConnection(username, password);
            BossConnectionWrapper.applyTenantContext(conn);
            return conn;
        }
    }
}
