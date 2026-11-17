package in.boss.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID tenantId,
        String firebaseUid,
        String email,
        String displayName,
        String phoneWa,
        String status,
        List<RoleMembership> roles,
        Instant createdAt) {
    public record RoleMembership(String role, UUID siteId, Instant grantedAt) { }
}
