package in.boss.sales.domain;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import in.boss.sales.api.SalesApiDtos.QuotationLineResponse;
import in.boss.sales.api.SalesApiDtos.QuotationResponse;
import in.boss.sales.domain.QuotationPdf.Party;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotationPdfTest {

    @Test
    void rendersAPrintableQuotationWithLetterheadLinesAndTotals() throws Exception {
        Party seller = new Party("Cursor Journey One Manufacturing", "Plant 1", "Pune", "Maharashtra",
                "27AAAAA0000A1Z5", "AAAAA0000A", "owner@example.com", "9800000000", "");
        Party buyer = new Party("North Works Private Limited", "12 Industrial Estate", "Pune", " Maharashtra ",
                "27ABCDE1234F1Z5", "ABCDE1234F", "meera@example.com", "9822001100", "Meera Shah");
        String text = render(seller, buyer);

        assertTrue(text.contains("QUOTATION"));
        assertTrue(text.contains("QT-20261117-B4857772"));
        assertTrue(text.contains("North Works Private Limited"));
        assertTrue(text.contains("12 Industrial Estate"));
        assertTrue(text.contains("GSTIN 27ABCDE1234F1Z5"));
        assertTrue(text.contains("PAN ABCDE1234F"));
        assertTrue(text.contains("DWG-440"));
        assertTrue(text.contains("INR 5,000.00"));
        assertTrue(text.matches("(?s).*CGST \\(9%\\)\\s*INR 450\\.00.*SGST \\(9%\\)\\s*INR 450\\.00.*"), text);
        assertFalse(text.contains("IGST"));
        assertTrue(text.matches("(?s).*Grand total\\s*INR 5,900\\.00.*"), text);
        assertTrue(text.contains("Ship with material certificate"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Maharashtra", "Andhra Pradesh"})
    void distinguishesInterstateRegistrationsRegardlessOfAddressStates(String buyerAddressState) throws Exception {
        Party seller = new Party("Seller", "", "", "Maharashtra", "29AAAAA0000A1Z5", "", "", "", "");
        Party buyer = new Party("Buyer", "", "", buyerAddressState, "24ABCDE1234F1Z5", "", "", "", "");
        String text = render(seller, buyer);
        assertTrue(text.matches("(?s).*IGST \\(18%\\)\\s*INR 900\\.00.*"), text);
        assertTrue(text.contains("GSTIN 24ABCDE1234F1Z5"));
        assertFalse(text.contains("CGST"));
    }

    @Test
    void missingJurisdictionBlocksOnlyTaxableDocuments() throws Exception {
        Party seller = new Party("Seller", "", "", "Maharashtra", "", "", "", "", "");
        Party buyer = new Party("Buyer", "", "", "", "", "", "", "", "");

        var error = assertThrows(ResponseStatusException.class, () -> render(seller, buyer));
        assertEquals(422, error.getStatusCode().value());
        assertTrue(error.getReason().contains("customer GSTIN or a recognized address state"));
        String text = render(seller, buyer, BigDecimal.ZERO);
        assertTrue(text.contains("GSTIN — PAN —"));
        assertTrue(text.matches("(?s).*Grand total\\s*INR 5,000\\.00.*"), text);
        assertFalse(text.contains("IGST"));
        assertFalse(text.contains("CGST"));
    }

    @Test
    void groupsLargeRupeeAmountsUsingIndianDigitGrouping() throws Exception {
        Party seller = new Party("Seller", "", "", "Karnataka", "", "", "", "", "");
        Party buyer = new Party("Buyer", "", "", "Gujarat", "", "", "", "", "");
        String text = render(seller, buyer, new BigDecimal("18"), new BigDecimal("1250000"));

        assertTrue(text.matches("(?s).*IGST \\(18%\\)\\s*INR 2,25,000\\.00.*"), text);
        assertTrue(text.matches("(?s).*Grand total\\s*INR 14,75,000\\.00.*"), text);
    }

    private static String render(Party seller, Party buyer) throws Exception {
        return render(seller, buyer, new BigDecimal("18"));
    }

    private static String render(Party seller, Party buyer, BigDecimal taxRate) throws Exception {
        return render(seller, buyer, taxRate, new BigDecimal("5000.00"));
    }

    private static String render(Party seller, Party buyer, BigDecimal taxRate, BigDecimal amount) throws Exception {
        QuotationLineResponse line = new QuotationLineResponse(
                UUID.randomUUID(), UUID.randomUUID(), "SHAFT-01", "Drive shaft",
                new BigDecimal("4"), UUID.randomUUID(), "NOS", new BigDecimal("1250"),
                amount, 2, taxRate, null, "DWG-440", "A", "Drive shaft", null);
        QuotationResponse quotation = new QuotationResponse(
                UUID.randomUUID(), "QT-20261117-B4857772", UUID.randomUUID(), UUID.randomUUID(),
                "North Works", 2, "accepted", LocalDate.of(2026, 12, 1), List.of(line), List.of(), Instant.parse("2026-11-17T06:31:24Z"),
                30, "Ex works", "Ship with material certificate", BigDecimal.ZERO, "OWNER_RECORDED",
                "Accepted by phone", UUID.randomUUID(), Instant.parse("2026-11-17T06:31:24Z"),
                UUID.randomUUID(), "CPO-20261117-D97F131E", "RFQ-20261117-F423026D", List.of(), "Net 30 days");
        byte[] pdf = QuotationPdf.render(quotation, seller, buyer);

        assertTrue(pdf.length > 2000);
        assertTrue(pdf[0] == '%' && pdf[1] == 'P');
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", " ");
        } finally {
            reader.close();
        }
    }
}
