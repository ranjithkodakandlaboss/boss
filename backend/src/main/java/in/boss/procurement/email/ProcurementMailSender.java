package in.boss.procurement.email;

public interface ProcurementMailSender {

    SendResult send(String toEmail, String subject, String html);

    record SendResult(boolean delivered, String providerMessageId, String errorMessage) {}
}
