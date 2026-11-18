package in.boss.shared.notifications;

import java.time.Instant;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationResponse(
            UUID id, String notificationType, String entityType, UUID entityId,
            String title, String body, boolean isRead, Instant createdAt
    ) {}
}
