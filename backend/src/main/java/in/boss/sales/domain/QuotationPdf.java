package in.boss.sales.domain;

import in.boss.sales.api.SalesApiDtos.QuotationLineResponse;
import in.boss.sales.api.SalesApiDtos.QuotationResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class QuotationPdf {

    private QuotationPdf() {}

    static byte[] render(QuotationResponse quotation) {
        List<String> lines = new ArrayList<>();
        lines.add("BOSS quotation " + quotation.quotationNumber() + " revision " + quotation.revision());
        lines.add("Customer: " + safe(quotation.customerName()));
        lines.add("Status: " + quotation.status());
        lines.add("Valid until: " + (quotation.validUntil() == null ? "-" : quotation.validUntil().toString()));
        lines.add("Payment terms (days): " + (quotation.paymentTerms() == null ? "-" : quotation.paymentTerms()));
        lines.add("Delivery: " + safe(quotation.deliveryTerms()));
        if (quotation.rfqNumber() != null) {
            lines.add("RFQ: " + quotation.rfqNumber());
        }
        lines.add("This document is the quotation PDF. Sending it to the customer happens outside BOSS.");
        BigDecimal goods = BigDecimal.ZERO;
        int seq = 1;
        for (QuotationLineResponse line : quotation.lines()) {
            goods = goods.add(line.lineTotal());
            lines.add(seq++ + ". " + safe(line.itemCode()) + " " + safe(line.itemName())
                    + " qty " + line.qty() + " " + safe(line.uomSymbol())
                    + " @ INR " + line.unitPrice() + " = INR " + line.lineTotal());
        }
        BigDecimal discount = quotation.discountAmount() == null ? BigDecimal.ZERO : quotation.discountAmount();
        lines.add("Goods INR " + goods.setScale(2) + "  Discount INR " + discount.setScale(2));
        lines.add("Payable before tax INR " + goods.subtract(discount).setScale(2));
        return document(lines);
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("\\", " ").replace("(", " ").replace(")", " ").replace("\r", " ").replace("\n", " ");
    }

    private static byte[] document(List<String> lines) {
        StringBuilder stream = new StringBuilder("BT /F1 11 Tf 48 800 Td 14 TL\n");
        for (String line : lines) {
            String text = line.length() > 110 ? line.substring(0, 110) : line;
            stream.append("(").append(text).append(") '\n");
        }
        stream.append("ET");
        byte[] content = stream.toString().getBytes(StandardCharsets.US_ASCII);
        String objects = """
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Count 1 /Kids [3 0 R] >> endobj
                3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
                4 0 obj << /Length %d >> stream
                """.formatted(content.length);
        String font = "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n";
        String header = "%PDF-1.4\n";
        byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        byte[] body = objects.getBytes(StandardCharsets.US_ASCII);
        byte[] end = "\nendstream endobj\n".getBytes(StandardCharsets.US_ASCII);
        byte[] fontBytes = font.getBytes(StandardCharsets.US_ASCII);
        int xref = head.length + body.length + content.length + end.length + fontBytes.length;
        String trailer = "xref\n0 6\n0000000000 65535 f \n"
                + offset(header.length())
                + "trailer << /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF";
        byte[] trailerBytes = trailer.getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = new byte[xref + trailerBytes.length];
        int cursor = 0;
        cursor = copy(pdf, cursor, head);
        cursor = copy(pdf, cursor, body);
        cursor = copy(pdf, cursor, content);
        cursor = copy(pdf, cursor, end);
        cursor = copy(pdf, cursor, fontBytes);
        copy(pdf, cursor, trailerBytes);
        return pdf;
    }

    private static String offset(int value) {
        return String.format("%010d 00000 n \n", value);
    }

    private static int copy(byte[] target, int cursor, byte[] source) {
        System.arraycopy(source, 0, target, cursor, source.length);
        return cursor + source.length;
    }
}
