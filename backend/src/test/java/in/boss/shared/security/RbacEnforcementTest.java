package in.boss.shared.security;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests verifying that @PreAuthorize RBAC annotations are enforced (issue #20).
 *
 * @EnableMethodSecurity is configured via BossSecurityConfig (auto-loaded by @SpringBootTest).
 * Tests confirm that each role combination at critical operations passes/fails as expected.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RbacEnforcementTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("boss_test")
            .withUsername("boss")
            .withPassword("boss");

    @DynamicPropertySource
    static void configureDs(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired RbacTestService rbacTestService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- @PreAuthorize(BossPermissions.OWNER) ----

    @Test
    void owner_canPerformOwnerOnlyOperation() {
        setAuth("OWNER");
        assertThat(rbacTestService.ownerOnlyOp()).isEqualTo("ok");
    }

    @Test
    void opsManager_cannotPerformOwnerOnlyOperation() {
        setAuth("OPS_MANAGER");
        assertThatThrownBy(() -> rbacTestService.ownerOnlyOp())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void storeKeeper_cannotPerformOwnerOnlyOperation() {
        setAuth("STORE_KEEPER");
        assertThatThrownBy(() -> rbacTestService.ownerOnlyOp())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- @PreAuthorize(BossPermissions.SALES_ACCESS) ----

    @Test
    void owner_canPerformSalesOperation() {
        setAuth("OWNER");
        assertThat(rbacTestService.salesOp()).isEqualTo("ok");
    }

    @Test
    void salesManager_canPerformSalesOperation() {
        setAuth("SALES_MANAGER");
        assertThat(rbacTestService.salesOp()).isEqualTo("ok");
    }

    @Test
    void opsManager_canPerformSalesOperation() {
        setAuth("OPS_MANAGER");
        assertThat(rbacTestService.salesOp()).isEqualTo("ok");
    }

    @Test
    void purchaseManager_cannotPerformSalesOperation() {
        setAuth("PURCHASE_MANAGER");
        assertThatThrownBy(() -> rbacTestService.salesOp())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- @PreAuthorize(BossPermissions.INVENTORY_WRITE) ----

    @Test
    void storeKeeper_canWriteInventory() {
        setAuth("STORE_KEEPER");
        assertThat(rbacTestService.inventoryWriteOp()).isEqualTo("ok");
    }

    @Test
    void productionSupervisor_cannotWriteInventory() {
        setAuth("PRODUCTION_SUPERVISOR");
        assertThatThrownBy(() -> rbacTestService.inventoryWriteOp())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- @PreAuthorize(BossPermissions.PLATFORM_ADMIN) ----

    @Test
    void platformAdmin_canPerformPlatformAdminOp() {
        setAuth("PLATFORM_ADMIN");
        assertThat(rbacTestService.platformAdminOp()).isEqualTo("ok");
    }

    @Test
    void owner_cannotPerformPlatformAdminOp() {
        setAuth("OWNER");
        assertThatThrownBy(() -> rbacTestService.platformAdminOp())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- @PreAuthorize(BossPermissions.ANY_TENANT_USER) ----

    @Test
    void anyAuthenticatedUser_canCallPublicTenantOp() {
        setAuth("FIELD_WORKER");
        assertThat(rbacTestService.anyUserOp()).isEqualTo("ok");
    }

    // ---- Helper ----

    private void setAuth(String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                "test-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ---- Test service with @PreAuthorize annotations ----

    @TestConfiguration
    static class RbacTestConfig {
        @Bean
        RbacTestService rbacTestService() {
            return new RbacTestService();
        }
    }

    @Service
    static class RbacTestService {

        @PreAuthorize(BossPermissions.OWNER)
        public String ownerOnlyOp() { return "ok"; }

        @PreAuthorize(BossPermissions.SALES_ACCESS)
        public String salesOp() { return "ok"; }

        @PreAuthorize(BossPermissions.INVENTORY_WRITE)
        public String inventoryWriteOp() { return "ok"; }

        @PreAuthorize(BossPermissions.PLATFORM_ADMIN)
        public String platformAdminOp() { return "ok"; }

        @PreAuthorize(BossPermissions.ANY_TENANT_USER)
        public String anyUserOp() { return "ok"; }
    }
}
