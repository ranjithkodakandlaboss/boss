package in.boss.sales.domain;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import in.boss.sales.api.SalesApiDtos.QuotationLineResponse;
import in.boss.sales.api.SalesApiDtos.QuotationResponse;
import in.boss.sales.domain.QuotationPdf.Party;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotationPdfTest {

    @Test
    void rendersAPrintableQuotationWithLetterheadLinesAndTotals() throws Exception {
        QuotationLineResponse line = new QuotationLineResponse(
                UUID.randomUUID(), UUID.randomUUID(), "SHAFT-01", "Drive shaft",
                new BigDecimal("4"), UUID.randomUUID(), "NOS", new BigDecimal("1250"),
                new BigDecimal("5000.00"), 2, new BigDecimal("18"), null, "DWG-440", "A", "Drive shaft", null);
        QuotationResponse quotation = new QuotationResponse(
                UUID.randomUUID(), "QT-20261117-B4857772", UUID.randomUUID(), UUID.randomUUID(),
                "North Works", 2, "accepted", LocalDate.of(2026, 12, 1), List.of(line), List.of(), Instant.parse("2026-11-17T06:31:24Z"),
                30, "Ex works", "Ship with material certificate", BigDecimal.ZERO, "OWNER_RECORDED",
                "Accepted by phone", UUID.randomUUID(), Instant.parse("2026-11-17T06:31:24Z"),
                UUID.randomUUID(), "CPO-20261117-D97F131E", "RFQ-20261117-F423026D", List.of(), "Net 30 days");
        Party seller = new Party("Cursor Journey One Manufacturing", "Plant 1", "Pune", "Maharashtra",
                "27AAAAA0000A1Z5", "AAAAA0000A", "owner@example.com", "9800000000", "");
        Party buyer = new Party("North Works Private Limited", "12 Industrial Estate", "Pune", "Maharashtra",
                "27ABCDE1234F1Z5", "ABCDE1234F", "meera@example.com", "9822001100", "Meera Shah");

        byte[] pdf = QuotationPdf.render(quotation, seller, buyer);

        assertTrue(pdf.length > 2000);
        assertTrue(pdf[0] == '%' && pdf[1] == 'P');
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertTrue(text.contains("QUOTATION"));
        assertTrue(text.contains("QT-20261117-B4857772"));
        assertTrue(text.contains("North Works Private Limited"));
        assertTrue(text.contains("DWG-440"));
        assertTrue(text.contains("INR 5,000.00"));
        assertTrue(text.contains("CGST + SGST"));
        assertTrue(text.contains("Grand total"));
        assertTrue(text.contains("Ship with material certificate"));
    }
}
