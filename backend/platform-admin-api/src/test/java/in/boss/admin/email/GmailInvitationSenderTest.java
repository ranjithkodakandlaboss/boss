package in.boss.admin.email;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GmailInvitationSenderTest {
    @Test
    void missingAppPasswordDoesNotOpenAConnection() {
        GmailInvitationSender sender = new GmailInvitationSender(
                "ranjith302@gmail.com",
                "   ",
                "/tmp/boss-gmail-app-password-missing");

        ResendEmailSender.EmailSendResult result = sender.sendInvitationEmail(
                "owner@example.com",
                "Virgin Surfaces",
                "http://virginsurfaces.34.54.120.120.nip.io",
                "http://virginsurfaces.34.54.120.120.nip.io/activate?token=test",
                Instant.now().plusSeconds(3600));

        assertThat(result.delivered()).isFalse();
        assertThat(result.errorMessage()).contains("app password");
    }
}
