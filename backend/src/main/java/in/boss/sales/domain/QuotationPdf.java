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
import in.boss.sales.api.SalesApiDtos.CpoResponse;
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
                 String email, String phone, String contact, String taxAddressState) {
        Party(String name, String address, String city, String state, String gstin, String pan,
              String email, String phone, String contact) {
            this(name, address, city, state, gstin, pan, email, phone, contact, state);
        }
    }

    private record Fact(String label, String value) {}
    private record Line(String description, BigDecimal qty, String uom, BigDecimal price,
                        BigDecimal gstRate, BigDecimal total) {}

    static byte[] render(QuotationResponse quotation, Party seller, Party buyer) {
        List<Line> lines = quotation.lines() == null ? List.of() : quotation.lines().stream()
                .map(l -> new Line(description(l.description(), l.itemName(), l.itemCode(),
                        l.drawingNumber(), l.drawingRevision()), l.qty(), l.uomSymbol(), l.unitPrice(),
                        l.gstRate(), l.lineTotal())).toList();
        return render("QUOTATION", quotation.quotationNumber() + "  ·  Revision " + quotation.revision(),
                "Quotation to", quotation.customerName(), List.of(
                        new Fact("Date", format(quotation.createdAt())),
                        new Fact("Valid until", format(quotation.validUntil())),
                        new Fact("RFQ", quotation.rfqNumber()),
                        new Fact("Payment", payment(quotation.paymentTermsText(), quotation.paymentTerms())),
                        new Fact("Delivery", quotation.deliveryTerms()),
                        new Fact("Status", quotation.status())),
                lines, quotation.discountAmount(), quotation.notes(),
                "This quotation uses the prices, quantities, and GST rates stored on the revision. "
                        + "It is the document to print or share. Accepting it records the customer's decision and does not reprice the lines.",
                seller, buyer);
    }

    static byte[] render(CpoResponse order, Party seller, Party buyer) {
        List<Line> lines = order.lines() == null ? List.of() : order.lines().stream()
                .map(l -> new Line(description(l.description(), l.itemName(), l.itemCode(),
                        l.drawingNumber(), l.drawingRevision()), l.qty(), l.uomSymbol(), l.unitPrice(),
                        l.gstRate(), l.lineTotal())).toList();
        String quotation = order.quotationNumber();
        if (quotation != null && order.quotationRevision() != null) {
            quotation += " rev " + order.quotationRevision();
        }
        return render("CUSTOMER PO", order.cpoNumber(), "Customer", order.customerName(), List.of(
                        new Fact("Order date", format(order.orderDate())),
                        new Fact("Required by", format(order.requiredByDate())),
                        new Fact("Customer PO ref", order.customerPoRef()),
                        new Fact("Quotation", quotation),
                        new Fact("RFQ", order.rfqNumber()),
                        new Fact("Direct order reason", order.directOrderReason()),
                        new Fact("Payment", payment(order.paymentTermsText(), order.paymentTerms())),
                        new Fact("Delivery", order.deliveryTerms()),
                        new Fact("Status", order.status())),
                lines, order.discountAmount(), order.notes(), null, seller, buyer);
    }

    private static byte[] render(String heading, String reference, String buyerLabel, String customerName,
                                 List<Fact> facts, List<Line> lines, BigDecimal discount, String notes,
                                 String footer, Party seller, Party buyer) {
        var gst = SalesPdfGst.calculate(lines.stream().map(line -> new SalesPdfGst.Line(line.total(), line.gstRate()))
                .toList(), discount, seller, buyer);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 36, 36, 36, 42);
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(letterhead(seller, heading));
            document.add(title(heading, reference));
            document.add(parties(buyerLabel, customerName, facts, buyer));
            document.add(lines(lines));
            document.add(totals(gst));
            document.add(notes(notes, footer));
            document.close();
            return out.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not render the " + heading + " PDF", exception);
        }
    }

    private static PdfPTable letterhead(Party seller, String heading) {
        PdfPTable table = new PdfPTable(new float[] {3f, 2f});
        table.setWidthPercentage(100);
        PdfPCell name = new PdfPCell(text(text(seller.name(), heading), 16, Font.BOLD, INK));
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

    private static Paragraph title(String heading, String reference) {
        Paragraph title = new Paragraph();
        title.setSpacingBefore(10);
        title.setSpacingAfter(2);
        title.add(new Phrase(heading + "\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, NAVY)));
        title.add(new Phrase(reference,
                FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED)));
        return title;
    }

    private static PdfPTable parties(String buyerLabel, String customerName, List<Fact> values, Party buyer) {
        PdfPTable table = new PdfPTable(new float[] {1.4f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(8);
        PdfPCell party = boxed(buyerLabel);
        party.addElement(line(text(buyer.name(), text(customerName, "—")), 11, Font.BOLD, INK));
        party.addElement(line(text(buyer.contact(), ""), 8, Font.NORMAL, MUTED));
        party.addElement(line(text(address(buyer), "—"), 8, Font.NORMAL, MUTED));
        party.addElement(line("GSTIN " + text(buyer.gstin(), "—") + "   PAN " + text(buyer.pan(), "—"), 8, Font.NORMAL, MUTED));
        table.addCell(party);
        PdfPCell facts = boxed("Document");
        for (Fact value : values) {
            facts.addElement(fact(value.label(), text(value.value(), "—")));
        }
        table.addCell(facts);
        return table;
    }

    private static PdfPTable lines(List<Line> rows) {
        PdfPTable table = new PdfPTable(new float[] {0.5f, 3.2f, 0.7f, 0.7f, 1.1f, 0.7f, 1.2f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12);
        for (String heading : List.of("#", "Description", "Qty", "UOM", "Rate", "GST", "Amount")) {
            table.addCell(head(heading));
        }
        if (rows.isEmpty()) {
            PdfPCell empty = body("No lines on this document", Element.ALIGN_LEFT);
            empty.setColspan(7);
            table.addCell(empty);
            return table;
        }
        int index = 1;
        for (Line line : rows) {
            table.addCell(body(String.valueOf(index++), Element.ALIGN_CENTER));
            table.addCell(body(line.description(), Element.ALIGN_LEFT));
            table.addCell(body(money(line.qty(), 3), Element.ALIGN_RIGHT));
            table.addCell(body(text(line.uom(), "—"), Element.ALIGN_CENTER));
            table.addCell(body(inr(line.price()), Element.ALIGN_RIGHT));
            table.addCell(body(line.gstRate() == null ? "—" : line.gstRate().stripTrailingZeros().toPlainString() + "%", Element.ALIGN_RIGHT));
            table.addCell(body(inr(line.total()), Element.ALIGN_RIGHT));
        }
        return table;
    }

    private static PdfPTable totals(SalesPdfGst.Totals gst) {
        PdfPTable table = new PdfPTable(new float[] {3f, 1.4f});
        table.setWidthPercentage(46);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(8);
        table.addCell(plain("Goods value"));
        table.addCell(amount(inr(gst.goods())));
        table.addCell(plain("Discount"));
        table.addCell(amount(inr(gst.discount())));
        for (var component : gst.tax()) {
            table.addCell(plain(component.name() + " (" + component.rate().stripTrailingZeros().toPlainString() + "%)"));
            table.addCell(amount(inr(component.amount())));
        }
        PdfPCell label = new PdfPCell(new Phrase("Grand total", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE)));
        label.setBackgroundColor(NAVY);
        label.setBorder(0);
        label.setPadding(6);
        table.addCell(label);
        PdfPCell value = new PdfPCell(new Phrase(inr(gst.grand()), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE)));
        value.setBackgroundColor(NAVY);
        value.setBorder(0);
        value.setHorizontalAlignment(Element.ALIGN_RIGHT);
        value.setPadding(6);
        table.addCell(value);
        return table;
    }

    private static Paragraph notes(String value, String footer) {
        Paragraph notes = new Paragraph();
        notes.setSpacingBefore(14);
        notes.add(new Phrase("Notes\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK)));
        notes.add(new Phrase(text(value, "No notes recorded.") + "\n\n",
                FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED)));
        if (footer != null) {
            notes.add(new Phrase(footer, FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED)));
        }
        return notes;
    }

    private static String description(String description, String itemName, String itemCode, String drawingNumber,
                                      String drawingRevision) {
        String name = text(description, text(itemName, text(itemCode, "Line")));
        String drawing = text(drawingNumber, "");
        if (!drawing.isBlank()) {
            name = drawing + (drawingRevision == null || drawingRevision.isBlank()
                    ? "" : " rev " + drawingRevision) + " — " + name;
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

    private static String format(java.time.LocalDate date) {
        return date == null ? "—" : date.format(DATES);
    }

    private static String payment(String terms, Integer days) {
        return text(terms, days == null ? "—" : days + " days");
    }

    private static String inr(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        String rupeesAndPaise = amount.setScale(2).toPlainString();
        int decimal = rupeesAndPaise.indexOf('.');
        String rupees = rupeesAndPaise.substring(0, decimal).replaceAll("(?<=\\d)(?=(?:\\d{2})*\\d{3}$)", ",");
        return "INR " + rupees + rupeesAndPaise.substring(decimal);
    }

    private static String money(BigDecimal value, int scale) {
        return value == null ? "—" : value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
