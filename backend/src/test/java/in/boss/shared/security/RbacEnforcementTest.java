package in.boss.shared.security;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying that @PreAuthorize RBAC annotations are enforced (issue #20).
 *
 * @EnableMethodSecurity is configured via BossSecurityConfig (auto-loaded by @SpringBootTest).
 * Tests confirm that each role combination at critical operations passes/fails as expected.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RbacEnforcementTest {

    @MockBean FirebaseApp firebaseApp;
    @MockBean FirebaseAuth firebaseAuth;

    @Autowired MockMvc mockMvc;

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

    // ---- #19: missing JWT returns 401 (Spring Security chain, not filter-only) ----

    @Test
    void missingJwt_onProtectedEndpoint_returns401() throws Exception {
        // No Authorization header → FirebaseJwtFilter passes through → Spring Security returns 401
        // (BossSecurityConfig configures authenticationEntryPoint returning SC_UNAUTHORIZED)
        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isUnauthorized());
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

    // ---- @PreAuthorize(BossPermissions.CONCESSION_APPROVE) ----

    @Test
    void owner_canApproveConcession() {
        setAuth("OWNER");
        assertThat(rbacTestService.approveConcessionOp()).isEqualTo("ok");
    }

    @Test
    void opsManager_canApproveConcession() {
        setAuth("OPS_MANAGER");
        assertThat(rbacTestService.approveConcessionOp()).isEqualTo("ok");
    }

    @Test
    void qualityInspector_cannotApproveConcession_unresolvedDecisionEnforced() {
        // Pending founder decision, QUALITY_INSPECTOR is denied concession approval
        setAuth("QUALITY_INSPECTOR");
        assertThatThrownBy(() -> rbacTestService.approveConcessionOp())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void storeKeeper_cannotApproveConcession() {
        setAuth("STORE_KEEPER");
        assertThatThrownBy(() -> rbacTestService.approveConcessionOp())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- @PreAuthorize(BossPermissions.COMMERCIAL_DATA_READ) ----

    @Test
    void commercialRoles_canReadCommercialData() {
        for (String role : List.of("OWNER", "OPS_MANAGER", "SALES_MANAGER", "PURCHASE_MANAGER", "ACCOUNTS_STAFF")) {
            setAuth(role);
            assertThat(rbacTestService.readCommercialDataOp()).isEqualTo("ok");
        }
    }

    @Test
    void floorRoles_cannotReadCommercialData() {
        for (String role : List.of("PRODUCTION_SUPERVISOR", "STORE_KEEPER", "QUALITY_INSPECTOR", "FIELD_WORKER")) {
            setAuth(role);
            assertThatThrownBy(() -> rbacTestService.readCommercialDataOp())
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ---- BossContext site scoping ----

    @Test
    void siteScopedUser_canOnlyAccessAssignedSite() {
        java.util.UUID assignedSite = java.util.UUID.randomUUID();
        java.util.UUID otherSite = java.util.UUID.randomUUID();

        in.boss.shared.context.BossContext siteScopedCtx = new in.boss.shared.context.BossContext(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.Set.of("PRODUCTION_SUPERVISOR"), assignedSite, "uid-supervisor");

        assertThat(siteScopedCtx.isSiteScoped()).isTrue();
        assertThat(siteScopedCtx.canAccessSite(assignedSite)).isTrue();
        assertThat(siteScopedCtx.canAccessSite(otherSite)).isFalse();

        in.boss.shared.context.BossContext tenantWideCtx = new in.boss.shared.context.BossContext(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.Set.of("OWNER"), null, "uid-owner");

        assertThat(tenantWideCtx.isSiteScoped()).isFalse();
        assertThat(tenantWideCtx.canAccessSite(assignedSite)).isTrue();
        assertThat(tenantWideCtx.canAccessSite(otherSite)).isTrue();
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

        @PreAuthorize(BossPermissions.CONCESSION_APPROVE)
        public String approveConcessionOp() { return "ok"; }

        @PreAuthorize(BossPermissions.COMMERCIAL_DATA_READ)
        public String readCommercialDataOp() { return "ok"; }
    }
}
