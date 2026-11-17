package in.boss.admin.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResendEmailSenderImplTest {
    @Test
    void testHtmlEmailFormattingSucceedsWithoutException() {
        ResendEmailSenderImpl sender = new ResendEmailSenderImpl(
                new ObjectMapper(),
                "test-api-key",
                null,
                "BOSS Onboarding <onboarding@resend.dev>"
        );

        // When sending with test API key (which fails network or mock gracefully),
        // formatting should succeed without UnknownFormatConversionException
        var result = sender.sendInvitationEmail(
                "ranjithkodakandla.boss@gmail.com",
                "Virgin Surfaces",
                "http://virgin-surfaces.34.54.120.120.nip.io",
                "http://virgin-surfaces.34.54.120.120.nip.io/activate?token=testtoken123",
                Instant.now().plusSeconds(3600)
        );

        assertThat(result).isNotNull();
    }
}
