package in.boss.sales.domain;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import in.boss.sales.api.SalesApiDtos.QuotationLineResponse;
import in.boss.sales.api.SalesApiDtos.QuotationResponse;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class QuotationPdf {

    private static final Color INK = new Color(15, 23, 42);
    private static final Color MUTED = new Color(71, 85, 105);
    private static final Color LINE = new Color(226, 232, 240);
    private static final Color BAND = new Color(241, 245, 249);
    private static final Color NAVY = new Color(15, 42, 68);
    private static final DateTimeFormatter DATES = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private QuotationPdf() {}

    record Party(String name, String address, String city, String state, String gstin, String pan,
                 String email, String phone, String contact) {}

    static byte[] render(QuotationResponse quotation, Party seller, Party buyer) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 36, 36, 36, 42);
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(letterhead(seller));
            document.add(title(quotation));
            document.add(parties(quotation, buyer));
            document.add(lines(quotation));
            document.add(totals(quotation, seller, buyer));
            document.add(notes(quotation));
            document.close();
            return out.toByteArray();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not render the quotation PDF", exception);
        }
    }

    private static PdfPTable letterhead(Party seller) {
        PdfPTable table = new PdfPTable(new float[] {3f, 2f});
        table.setWidthPercentage(100);
        PdfPCell name = new PdfPCell(text(text(seller.name(), "Quotation"), 16, Font.BOLD, INK));
        name.setBorder(0);
        name.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(name);
        PdfPCell meta = new PdfPCell();
        meta.setBorder(0);
        meta.setHorizontalAlignment(Element.ALIGN_RIGHT);
        meta.addElement(right("GSTIN " + text(seller.gstin(), "—"), 8, MUTED));
        meta.addElement(right("PAN " + text(seller.pan(), "—"), 8, MUTED));
        meta.addElement(right(address(seller), 8, MUTED));
        meta.addElement(right(join(seller.email(), seller.phone()), 8, MUTED));
        table.addCell(meta);
        return table;
    }

    private static Paragraph title(QuotationResponse quotation) {
        Paragraph title = new Paragraph();
        title.setSpacingBefore(10);
        title.setSpacingAfter(2);
        title.add(new Phrase("QUOTATION\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, NAVY)));
        title.add(new Phrase(quotation.quotationNumber() + "  ·  Revision " + quotation.revision(),
                FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED)));
        return title;
    }

    private static PdfPTable parties(QuotationResponse quotation, Party buyer) {
        PdfPTable table = new PdfPTable(new float[] {1.4f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(8);
        PdfPCell party = boxed("Quotation to");
        party.addElement(line(text(buyer.name(), quotation.customerName()), 11, Font.BOLD, INK));
        party.addElement(line(text(buyer.contact(), ""), 8, Font.NORMAL, MUTED));
        party.addElement(line(address(buyer), 8, Font.NORMAL, MUTED));
        party.addElement(line("GSTIN " + text(buyer.gstin(), "—") + "   PAN " + text(buyer.pan(), "—"), 8, Font.NORMAL, MUTED));
        table.addCell(party);
        PdfPCell facts = boxed("Document");
        facts.addElement(fact("Date", format(quotation.createdAt())));
        facts.addElement(fact("Valid until", quotation.validUntil() == null ? "—" : quotation.validUntil().format(DATES)));
        facts.addElement(fact("RFQ", text(quotation.rfqNumber(), "—")));
        facts.addElement(fact("Payment", quotation.paymentTerms() == null ? "—" : quotation.paymentTerms() + " days"));
        facts.addElement(fact("Delivery", text(quotation.deliveryTerms(), "—")));
        facts.addElement(fact("Status", quotation.status()));
        table.addCell(facts);
        return table;
    }

    private static PdfPTable lines(QuotationResponse quotation) {
        PdfPTable table = new PdfPTable(new float[] {0.5f, 3.2f, 0.7f, 0.7f, 1.1f, 0.7f, 1.2f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12);
        for (String heading : List.of("#", "Description", "Qty", "UOM", "Rate", "GST", "Amount")) {
            table.addCell(head(heading));
        }
        List<QuotationLineResponse> rows = quotation.lines() == null ? List.of() : quotation.lines();
        if (rows.isEmpty()) {
            PdfPCell empty = body("No lines on this quotation", Element.ALIGN_LEFT);
            empty.setColspan(7);
            table.addCell(empty);
            return table;
        }
        int index = 1;
        for (QuotationLineResponse line : rows) {
            table.addCell(body(String.valueOf(index++), Element.ALIGN_CENTER));
            table.addCell(body(description(line), Element.ALIGN_LEFT));
            table.addCell(body(money(line.qty(), 3), Element.ALIGN_RIGHT));
            table.addCell(body(text(line.uomSymbol(), "—"), Element.ALIGN_CENTER));
            table.addCell(body(inr(line.unitPrice()), Element.ALIGN_RIGHT));
            table.addCell(body(line.gstRate() == null ? "—" : line.gstRate().stripTrailingZeros().toPlainString() + "%", Element.ALIGN_RIGHT));
            table.addCell(body(inr(line.lineTotal()), Element.ALIGN_RIGHT));
        }
        return table;
    }

    private static PdfPTable totals(QuotationResponse quotation, Party seller, Party buyer) {
        BigDecimal goods = BigDecimal.ZERO;
        BigDecimal gst = BigDecimal.ZERO;
        if (quotation.lines() != null) {
            for (QuotationLineResponse line : quotation.lines()) {
                BigDecimal amount = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
                goods = goods.add(amount);
                BigDecimal rate = line.gstRate() == null ? BigDecimal.ZERO : line.gstRate();
                gst = gst.add(amount.multiply(rate).movePointLeft(2));
            }
        }
        gst = gst.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = quotation.discountAmount() == null ? BigDecimal.ZERO : quotation.discountAmount();
        BigDecimal grand = goods.subtract(discount).add(gst).setScale(2, RoundingMode.HALF_UP);
        PdfPTable table = new PdfPTable(new float[] {3f, 1.4f});
        table.setWidthPercentage(46);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(8);
        table.addCell(plain("Goods value"));
        table.addCell(amount(inr(goods)));
        table.addCell(plain("Discount"));
        table.addCell(amount(inr(discount)));
        table.addCell(plain(taxLabel(seller, buyer)));
        table.addCell(amount(inr(gst)));
        PdfPCell label = new PdfPCell(new Phrase("Grand total", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE)));
        label.setBackgroundColor(NAVY);
        label.setBorder(0);
        label.setPadding(6);
        table.addCell(label);
        PdfPCell value = new PdfPCell(new Phrase(inr(grand), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE)));
        value.setBackgroundColor(NAVY);
        value.setBorder(0);
        value.setHorizontalAlignment(Element.ALIGN_RIGHT);
        value.setPadding(6);
        table.addCell(value);
        return table;
    }

    private static Paragraph notes(QuotationResponse quotation) {
        Paragraph notes = new Paragraph();
        notes.setSpacingBefore(14);
        notes.add(new Phrase("Notes\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK)));
        notes.add(new Phrase(text(quotation.notes(), "No notes recorded.") + "\n\n",
                FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED)));
        notes.add(new Phrase("This quotation uses the prices, quantities, and GST rates stored on the revision. "
                        + "It is the document to print or share. Accepting it records the customer's decision and does not reprice the lines.",
                FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED)));
        return notes;
    }

    private static String taxLabel(Party seller, Party buyer) {
        if (seller.state() == null || buyer.state() == null || seller.state().isBlank() || buyer.state().isBlank()) {
            return "GST";
        }
        return seller.state().equalsIgnoreCase(buyer.state()) ? "CGST + SGST" : "IGST";
    }

    private static String description(QuotationLineResponse line) {
        String name = text(line.description(), text(line.itemName(), text(line.itemCode(), "Line")));
        String drawing = text(line.drawingNumber(), "");
        if (!drawing.isBlank()) {
            name = drawing + (line.drawingRevision() == null || line.drawingRevision().isBlank()
                    ? "" : " rev " + line.drawingRevision()) + " — " + name;
        }
        return name;
    }

    private static PdfPCell head(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, MUTED)));
        cell.setBackgroundColor(BAND);
        cell.setBorderColor(LINE);
        cell.setPadding(5);
        return cell;
    }

    private static PdfPCell body(String value, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(value, FontFactory.getFont(FontFactory.HELVETICA, 8, INK)));
        cell.setBorderColor(LINE);
        cell.setPadding(5);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private static PdfPCell boxed(String heading) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(LINE);
        cell.setPadding(8);
        cell.addElement(line(heading.toUpperCase(Locale.ENGLISH), 7, Font.BOLD, MUTED));
        return cell;
    }

    private static Paragraph text(String value, int size, int style, Color color) {
        return new Paragraph(value, FontFactory.getFont(style == Font.BOLD ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, size, color));
    }

    private static Paragraph line(String value, int size, int style, Color color) {
        Paragraph paragraph = text(value == null || value.isBlank() ? "" : value, size, style, color);
        paragraph.setSpacingAfter(1);
        return paragraph;
    }

    private static Paragraph right(String value, int size, Color color) {
        Paragraph paragraph = text(value, size, Font.NORMAL, color);
        paragraph.setAlignment(Element.ALIGN_RIGHT);
        return paragraph;
    }

    private static Paragraph fact(String label, String value) {
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Phrase(label + "  ", FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED)));
        paragraph.add(new Phrase(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, INK)));
        paragraph.setSpacingAfter(2);
        return paragraph;
    }

    private static PdfPCell plain(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value, FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED)));
        cell.setBorder(0);
        cell.setPadding(3);
        return cell;
    }

    private static PdfPCell amount(String value) {
        PdfPCell cell = plain(value);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static String address(Party party) {
        return join(party.address(), join(party.city(), party.state()));
    }

    private static String join(String left, String right) {
        boolean leftBlank = left == null || left.isBlank();
        boolean rightBlank = right == null || right.isBlank();
        if (leftBlank && rightBlank) return "";
        if (leftBlank) return right;
        if (rightBlank) return left;
        return left + ", " + right;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String format(java.time.Instant instant) {
        return instant == null ? "—" : DATES.format(instant.atZone(java.time.ZoneId.of("Asia/Kolkata")));
    }

    private static String inr(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        return "INR " + String.format(Locale.ENGLISH, "%,.2f", amount);
    }

    private static String money(BigDecimal value, int scale) {
        return value == null ? "—" : value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
