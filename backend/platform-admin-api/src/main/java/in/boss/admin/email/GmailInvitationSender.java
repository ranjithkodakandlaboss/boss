package in.boss.admin.email;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Sends the existing owner invitation through Gmail SMTP.
 * Active only when {@code boss.email.provider=gmail}. A missing app password
 * fails the send and does not open a connection.
 */
@Component
@Primary
@ConditionalOnProperty(name = "boss.email.provider", havingValue = "gmail")
public class GmailInvitationSender implements ResendEmailSender {
    private static final Logger log = LoggerFactory.getLogger(GmailInvitationSender.class);

    private final String username;
    private final String password;
    private final String passwordFile;

    public GmailInvitationSender(
            @Value("${boss.email.gmail-username:${GMAIL_USERNAME:}}") String username,
            @Value("${boss.email.gmail-app-password:${GMAIL_APP_PASSWORD:}}") String password,
            @Value("${boss.email.gmail-app-password-file:${GMAIL_APP_PASSWORD_FILE:/secrets/gmail-app-password}}") String passwordFile) {
        this.username = username;
        this.password = password;
        this.passwordFile = passwordFile;
    }

    @Override
    public EmailSendResult sendInvitationEmail(String toEmail, String tenantName, String workspaceUrl, String activationLink, Instant expiresAt) {
        String appPassword = resolvePassword();
        if (appPassword == null || appPassword.isBlank()) {
            String message = "Gmail app password is not configured. Create one under the Google account Security page and store it as boss-gmail-app-password-dev.";
            log.warn(message);
            return new EmailSendResult(false, null, message);
        }
        if (username == null || username.isBlank()) {
            return new EmailSendResult(false, null, "Gmail username is not configured.");
        }
        String formattedExpiry = DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm 'UTC'")
                .withZone(ZoneId.of("UTC"))
                .format(expiresAt);
        try {
            Properties properties = new Properties();
            properties.put("mail.smtp.host", "smtp.gmail.com");
            properties.put("mail.smtp.port", "587");
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.connectiontimeout", "10000");
            properties.put("mail.smtp.timeout", "15000");
            Session session = Session.getInstance(properties, new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(username, appPassword);
                }
            });
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username, "BOSS Onboarding", StandardCharsets.UTF_8.name()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject("You have been invited to join BOSS — " + tenantName, StandardCharsets.UTF_8.name());
            message.setContent(html(tenantName, workspaceUrl, activationLink, formattedExpiry), "text/html; charset=UTF-8");
            Transport.send(message);
            log.info("Owner invitation sent with Gmail to {}", toEmail);
            return new EmailSendResult(true, null, null);
        } catch (Exception exception) {
            log.error("Gmail invitation was not sent", exception);
            return new EmailSendResult(false, null, "Gmail did not send the invitation: " + exception.getMessage());
        }
    }

    private String resolvePassword() {
        if (password != null && !password.isBlank()) return password.replaceAll("\\s+", "");
        if (passwordFile == null || passwordFile.isBlank()) return null;
        try {
            Path path = Path.of(passwordFile);
            if (!Files.exists(path)) return null;
            String fileContent = Files.readString(path).replaceAll("\\s+", "");
            return fileContent.isEmpty() ? null : fileContent;
        } catch (Exception exception) {
            log.debug("Gmail app password file could not be read at {}: {}", passwordFile, exception.getMessage());
            return null;
        }
    }

    private static String html(String tenantName, String workspaceUrl, String activationLink, String formattedExpiry) {
        String workspaceSection = "";
        if (workspaceUrl != null && !workspaceUrl.isBlank()) {
            workspaceSection = """
                <div style="background-color: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px 16px; margin: 16px 0;">
                  <span style="font-size: 12px; font-weight: 600; text-transform: uppercase; color: #64748b; letter-spacing: 0.5px; display: block; margin-bottom: 4px;">Customer Workspace URL</span>
                  <a href="%s" style="color: #2563eb; text-decoration: none; font-weight: 600; font-size: 14px; word-break: break-all;">%s</a>
                </div>
                """.formatted(workspaceUrl, workspaceUrl);
        }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 40px 16px; color: #0f172a;">
              <table align="center" width="100%%" style="max-width: 580px; background-color: #ffffff; border: 1px solid #e2e8f0; border-radius: 8px;">
                <tr><td style="padding: 36px 32px;">
                  <h2 style="margin: 0 0 16px 0;">Welcome to BOSS</h2>
                  <p>You have been invited as the initial <strong>Administrator</strong> for <strong>%s</strong>.</p>
                  %s
                  <p>To complete your organization setup and access your workspace, activate your account and set your password.</p>
                  <p><a href="%s">Activate Your Account</a></p>
                  <p>%s</p>
                  <p>This invitation link is valid until %s.</p>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(tenantName, workspaceSection, activationLink, activationLink, formattedExpiry);
    }
}
