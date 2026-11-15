package in.boss.masterdata;

import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Set;
import java.util.UUID;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class BaseMasterDataIntegrationTest {

    public static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("boss_test")
                .withUsername("boss")
                .withPassword("boss");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        BossContextHolder.clear();
    }

    protected UUID provisionTenant(String slug) {
        BossContextHolder.clear();
        UUID groupId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customer_groups (name, slug) VALUES (?, ?) RETURNING id::text",
                String.class, slug + " Group", slug + "-grp-" + UUID.randomUUID().toString().substring(0, 8)));
        return UUID.fromString(jdbc.queryForObject(
                "INSERT INTO tenant_registry (customer_group_id, name, slug, status) " +
                "VALUES (?, ?, ?, 'active') RETURNING id::text",
                String.class, groupId, slug + " Tenant", slug + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    protected void setTenantContext(UUID tenantId, String... roles) {
        Set<String> roleSet = roles.length > 0 ? Set.of(roles) : Set.of("OWNER");
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), roleSet, null, null));
    }
}
