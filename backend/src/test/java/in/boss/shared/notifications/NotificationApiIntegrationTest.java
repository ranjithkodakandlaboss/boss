package in.boss.shared.notifications;

import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(roles = "OWNER")
@AutoConfigureMockMvc
class NotificationApiIntegrationTest extends BaseMasterDataIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID userA;
    private UUID userB;
    private UUID otherTenantUser;
    private UUID notificationA;
    private UUID notificationB;
    private UUID notificationOtherTenant;

    @BeforeEach
    void fixtures() {
        tenantId = provisionTenant("notifications");
        otherTenantId = provisionTenant("notifications-other");
        userA = createUser(tenantId, "a");
        userB = createUser(tenantId, "b");
        otherTenantUser = createUser(otherTenantId, "other");
        notificationA = createNotification(tenantId, userA, "maintenance_asset_impact");
        notificationB = createNotification(tenantId, userB, "maintenance_asset_impact");
        notificationOtherTenant = createNotification(otherTenantId, otherTenantUser, "maintenance_asset_impact");
    }

    @Test
    void feedIsScopedToAuthenticatedTenantAndRecipient() throws Exception {
        as(tenantId, userA);
        mockMvc.perform(get("/api/v1/notifications").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(notificationA.toString()));

        as(tenantId, userB);
        mockMvc.perform(get("/api/v1/notifications").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(notificationB.toString()));

        as(otherTenantId, otherTenantUser);
        mockMvc.perform(get("/api/v1/notifications").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(notificationOtherTenant.toString()));
    }

    @Test
    void onlyTheRecipientCanMarkTheirNotificationRead() throws Exception {
        as(tenantId, userB);
        mockMvc.perform(post("/api/v1/notifications/" + notificationA + "/read")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        as(tenantId, userA);
        mockMvc.perform(post("/api/v1/notifications/" + notificationA + "/read")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isRead").value(true));

        as(otherTenantId, otherTenantUser);
        mockMvc.perform(post("/api/v1/notifications/" + notificationA + "/read")
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    private UUID createUser(UUID tenant, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO app_users (tenant_id, firebase_uid, email, display_name)
                VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, tenant, "notification-" + UUID.randomUUID(), suffix + "@example.test", suffix);
    }

    private UUID createNotification(UUID tenant, UUID recipient, String type) {
        return jdbc.queryForObject("""
                INSERT INTO notifications (tenant_id, user_id, notification_type, entity_type, title, body)
                VALUES (?, ?, ?, 'maintenance_event', 'Asset unavailable', 'Review the affected stage')
                RETURNING id
                """, UUID.class, tenant, recipient, type);
    }

    private void as(UUID tenant, UUID userId) {
        BossContextHolder.set(new BossContext(tenant, userId, Set.of("OWNER"), null, null));
    }
}
