package in.boss.admin.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ResendEmailSenderImpl implements ResendEmailSender {
    private static final Logger log = LoggerFactory.getLogger(ResendEmailSenderImpl.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String explicitApiKey;
    private final String apiKeyFilePath;
    private final String fromAddress;

    public ResendEmailSenderImpl(
            ObjectMapper mapper,
            @Value("${boss.email.resend-api-key:${RESEND_API_KEY:}}") String explicitApiKey,
            @Value("${boss.email.resend-api-key-file:${RESEND_API_KEY_FILE:/secrets/resend-api-key}}") String apiKeyFilePath,
            @Value("${boss.email.from-address:${RESEND_FROM_EMAIL:BOSS Onboarding <onboarding@resend.dev>}}") String fromAddress) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.explicitApiKey = explicitApiKey;
        this.apiKeyFilePath = apiKeyFilePath;
        this.fromAddress = fromAddress;
    }

    @Override
    public EmailSendResult sendInvitationEmail(String toEmail, String tenantName, String workspaceUrl, String activationLink, Instant expiresAt) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            String message = "Resend API key is not configured. Add a secret version to GCP Secret Manager [boss-resend-api-key-dev] or set RESEND_API_KEY.";
            log.warn(message);
            return new EmailSendResult(false, null, message);
        }

        String formattedExpiry = DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm 'UTC'")
                .withZone(ZoneId.of("UTC"))
                .format(expiresAt);

        String htmlBody = buildHtmlEmail(tenantName, workspaceUrl, activationLink, formattedExpiry);

        try {
            Map<String, Object> payload = Map.of(
                    "from", fromAddress,
                    "to", List.of(toEmail),
                    "subject", "You have been invited to join BOSS — " + tenantName,
                    "html", htmlBody
            );

            String requestJson = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "BOSS-Platform-Admin/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                JsonNode root = mapper.readTree(response.body());
                String id = root.path("id").asText();
                log.info("Invitation email sent successfully via Resend to recipient for tenant={} resendId={}", tenantName, id);
                return new EmailSendResult(true, id, null);
            } else {
                String errorDetail = parseErrorMessage(response.body(), response.statusCode());
                log.warn("Resend API rejected email sending for tenant={} status={}: {}", tenantName, response.statusCode(), errorDetail);
                return new EmailSendResult(false, null, "Resend error (" + response.statusCode() + "): " + errorDetail);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Invitation email delivery interrupted", e);
            return new EmailSendResult(false, null, "Email delivery was interrupted");
        } catch (Exception e) {
            log.error("Unexpected error delivering email via Resend", e);
            return new EmailSendResult(false, null, "Failed to send email via Resend: " + e.getMessage());
        }
    }

    private String resolveApiKey() {
        if (explicitApiKey != null && !explicitApiKey.isBlank()) {
            return explicitApiKey.trim();
        }
        if (apiKeyFilePath != null && !apiKeyFilePath.isBlank()) {
            try {
                Path path = Paths.get(apiKeyFilePath);
                if (Files.exists(path)) {
                    String fileContent = Files.readString(path).trim();
                    if (!fileContent.isEmpty()) return fileContent;
                }
            } catch (IOException e) {
                log.debug("Resend API key file could not be read at {}: {}", apiKeyFilePath, e.getMessage());
            }
        }
        return null;
    }

    private String parseErrorMessage(String body, int statusCode) {
        if (body == null || body.isBlank()) return "HTTP " + statusCode;
        try {
            JsonNode root = mapper.readTree(body);
            if (root.has("message")) return root.get("message").asText();
            if (root.has("error")) return root.get("error").asText();
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private String buildHtmlEmail(String tenantName, String workspaceUrl, String activationLink, String formattedExpiry) {
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
        <head>
          <meta charset="utf-8">
          <title>BOSS Workspace Invitation</title>
        </head>
        <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 40px 16px; color: #0f172a;">
          <table align="center" width="100%" style="max-width: 580px; background-color: #ffffff; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.06); border-spacing: 0;">
            <tr>
              <td style="background-color: #0f172a; padding: 24px 32px; text-align: left;">
                <span style="display: inline-block; background-color: #2563eb; color: #ffffff; font-weight: 800; font-size: 18px; width: 36px; height: 36px; line-height: 36px; text-align: center; border-radius: 6px; margin-right: 12px; vertical-align: middle;">B</span>
                <span style="color: #ffffff; font-size: 18px; font-weight: 700; letter-spacing: 0.5px; vertical-align: middle;">BOSS</span>
                <span style="color: #94a3b8; font-size: 13px; margin-left: 8px; vertical-align: middle; text-transform: uppercase; letter-spacing: 0.5px;">Platform Operations</span>
              </td>
            </tr>
            <tr>
              <td style="padding: 36px 32px;">
                <h2 style="margin: 0 0 16px 0; color: #0f172a; font-size: 20px; font-weight: 600;">Welcome to BOSS</h2>
                <p style="margin: 0 0 16px 0; line-height: 1.6; color: #334155; font-size: 15px;">
                  You have been invited as the initial <strong>Administrator</strong> for <strong>%s</strong>.
                </p>
                %s
                <p style="margin: 0 0 28px 0; line-height: 1.6; color: #334155; font-size: 15px;">
                  To complete your organization setup and access your workspace, please activate your account and set your password.
                </p>
                <div style="text-align: center; margin: 32px 0;">
                  <a href="%s" style="background-color: #2563eb; color: #ffffff; text-decoration: none; padding: 12px 28px; border-radius: 6px; font-weight: 600; font-size: 15px; display: inline-block;">Activate Your Account</a>
                </div>
                <p style="margin: 0 0 8px 0; font-size: 13px; color: #64748b;">
                  If the button above does not work, copy and paste this secure link into your browser:
                </p>
                <p style="margin: 0 0 28px 0; font-size: 13px; color: #2563eb; word-break: break-all;">
                  %s
                </p>
                <p style="margin: 0; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; padding-top: 16px;">
                  This invitation link is valid until %s. If you did not expect this invitation, you can ignore this email.
                </p>
              </td>
            </tr>
            <tr>
              <td style="background-color: #f8fafc; padding: 16px 32px; border-top: 1px solid #e2e8f0; text-align: center; font-size: 12px; color: #94a3b8;">
                BOSS Manufacturing Execution Platform &bull; Automated Platform Operations
              </td>
            </tr>
          </table>
        </body>
        </html>
        """.formatted(tenantName, workspaceSection, activationLink, activationLink, formattedExpiry);
    }
}
