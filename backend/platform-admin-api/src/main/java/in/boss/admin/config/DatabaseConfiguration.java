package in.boss.admin.config;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("!test")
public class DatabaseConfiguration {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource platformAdminDataSource(Environment environment,
            @Value("${boss.admin-db.password-file:/secrets/admin-db-password}") String passwordFile) throws IOException {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        dataSource.setPassword(Files.readString(Path.of(passwordFile)).strip());
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }
}
