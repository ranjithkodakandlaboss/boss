package in.boss.admin.email;

import java.time.Instant;

public interface ResendEmailSender {
    EmailSendResult sendInvitationEmail(String toEmail, String tenantName, String activationLink, Instant expiresAt);

    record EmailSendResult(boolean delivered, String resendId, String errorMessage) {}
}
