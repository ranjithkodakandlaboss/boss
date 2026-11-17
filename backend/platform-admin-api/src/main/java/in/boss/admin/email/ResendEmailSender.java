package in.boss.admin.email;

import java.time.Instant;

public interface ResendEmailSender {
    default EmailSendResult sendInvitationEmail(String toEmail, String tenantName, String activationLink, Instant expiresAt) {
        return sendInvitationEmail(toEmail, tenantName, null, activationLink, expiresAt);
    }

    EmailSendResult sendInvitationEmail(String toEmail, String tenantName, String workspaceUrl, String activationLink, Instant expiresAt);

    record EmailSendResult(boolean delivered, String resendId, String errorMessage) {}
}
