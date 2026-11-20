package in.boss.procurement.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Sends procurement mail through the same Resend or Gmail configuration used
 * for owner invitations. A missing credential records a failure and does not
 * invent a delivered message.
 */
@Component
public class ApprovedProcurementMailSender implements ProcurementMailSender {

    private static final Logger log = LoggerFactory.getLogger(ApprovedProcurementMailSender.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String provider;
    private final String resendApiKey;
    private final String resendApiKeyFile;
    private final String fromAddress;
    private final String gmailUsername;
    private final String gmailPassword;
    private final String gmailPasswordFile;

    public ApprovedProcurementMailSender(
            ObjectMapper mapper,
            @Value("${boss.email.provider:resend}") String provider,
            @Value("${boss.email.resend-api-key:${RESEND_API_KEY:}}") String resendApiKey,
            @Value("${boss.email.resend-api-key-file:${RESEND_API_KEY_FILE:/secrets/resend-api-key}}") String resendApiKeyFile,
            @Value("${boss.email.from-address:${RESEND_FROM_EMAIL:BOSS <onboarding@resend.dev>}}") String fromAddress,
            @Value("${boss.email.gmail-username:${GMAIL_USERNAME:}}") String gmailUsername,
            @Value("${boss.email.gmail-app-password:${GMAIL_APP_PASSWORD:}}") String gmailPassword,
            @Value("${boss.email.gmail-app-password-file:${GMAIL_APP_PASSWORD_FILE:/secrets/gmail-app-password}}") String gmailPasswordFile) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.provider = provider;
        this.resendApiKey = resendApiKey;
        this.resendApiKeyFile = resendApiKeyFile;
        this.fromAddress = fromAddress;
        this.gmailUsername = gmailUsername;
        this.gmailPassword = gmailPassword;
        this.gmailPasswordFile = gmailPasswordFile;
    }

    @Override
    public SendResult send(String toEmail, String subject, String html) {
        if ("gmail".equalsIgnoreCase(provider)) {
            return sendGmail(toEmail, subject, html);
        }
        return sendResend(toEmail, subject, html);
    }

    private SendResult sendResend(String toEmail, String subject, String html) {
        String apiKey = secret(resendApiKey, resendApiKeyFile);
        if (apiKey == null) {
            log.warn("Supplier invitation email not sent: Resend API key is not configured (boss-resend-api-key-dev / RESEND_API_KEY)");
            return new SendResult(false, null,
                    "Email could not be sent — mail delivery is not configured. Ask an administrator to check mail settings.");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "from", fromAddress,
                    "to", List.of(toEmail),
                    "subject", subject,
                    "html", html));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "BOSS-Procurement/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                JsonNode root = mapper.readTree(response.body());
                return new SendResult(true, root.path("id").asText(null), null);
            }
            return new SendResult(false, null, "Resend error (" + response.statusCode() + ")");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SendResult(false, null, "Email delivery was interrupted");
        } catch (Exception exception) {
            log.warn("Supplier quotation email was not sent through Resend");
            return new SendResult(false, null, "Failed to send email via Resend");
        }
    }

    private SendResult sendGmail(String toEmail, String subject, String html) {
        String password = secret(gmailPassword, gmailPasswordFile);
        if (password == null) {
            return new SendResult(false, null,
                    "Gmail app password is not configured. Store it as boss-gmail-app-password-dev.");
        }
        if (gmailUsername == null || gmailUsername.isBlank()) {
            return new SendResult(false, null, "Gmail username is not configured.");
        }
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
                    return new jakarta.mail.PasswordAuthentication(gmailUsername, password.replaceAll("\\s+", ""));
                }
            });
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(gmailUsername, "BOSS", StandardCharsets.UTF_8.name()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setContent(html, "text/html; charset=UTF-8");
            Transport.send(message);
            return new SendResult(true, null, null);
        } catch (Exception exception) {
            log.warn("Supplier quotation email was not sent through Gmail");
            return new SendResult(false, null, "Gmail did not send the quotation request");
        }
    }

    private static String secret(String explicit, String file) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(file);
            if (!Files.exists(path)) {
                return null;
            }
            String value = Files.readString(path).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception exception) {
            return null;
        }
    }
}
