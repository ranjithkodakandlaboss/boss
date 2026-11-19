package in.boss.sales.domain;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import in.boss.sales.api.SalesApiDtos.CpoLineResponse;
import in.boss.sales.api.SalesApiDtos.CpoResponse;
import in.boss.sales.domain.QuotationPdf.Party;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerPoPdfTest {
    @Test
    void rendersBuyerIdentityAndMixedGstRatesAfterOrderDiscount() throws Exception {
        var seller = new Party("Seller", "Plant 1", "Bengaluru", "Karnataka", "29AAAAA0000A1Z5", "", "", "", "");
        var buyer = new Party("Buyer Ltd", "12 Industrial Estate, Building B, 560001", "Bengaluru",
                " karnataka ", "", "ABCDE1234F", "", "", "Buyer contact");
        var order = new CpoResponse(UUID.randomUUID(), "CPO-20261119-TEST", UUID.randomUUID(), "Buyer",
                null, null, null, null, null, "BUYER-123", "open", LocalDate.of(2026, 11, 19), null, 30,
                "Order note", new BigDecimal("200"), null, null, null, null,
                List.of(line("Bracket", "18"), line("Fitting", "5")), null, "Net 30", null, "Ex works");

        PdfReader reader = new PdfReader(QuotationPdf.render(order, seller, buyer));
        String text;
        try {
            text = new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", " ");
        } finally {
            reader.close();
        }

        assertTrue(text.contains("CUSTOMER PO"));
        assertTrue(text.contains("BUYER-123"));
        assertTrue(text.contains("Buyer Ltd"));
        assertTrue(text.contains("12 Industrial Estate, Building B, 560001"));
        assertTrue(text.contains("GSTIN — PAN ABCDE1234F"));
        assertTrue(text.matches("(?s).*CGST \\(2.5%\\)\\s*INR 22\\.50.*SGST \\(2.5%\\)\\s*INR 22\\.50.*"), text);
        assertTrue(text.matches("(?s).*CGST \\(9%\\)\\s*INR 81\\.00.*SGST \\(9%\\)\\s*INR 81\\.00.*"), text);
        assertTrue(text.matches("(?s).*Grand total\\s*INR 2,007\\.00.*"), text);
        assertFalse(text.contains("IGST"));
    }

    private static CpoLineResponse line(String name, String rate) {
        return new CpoLineResponse(UUID.randomUUID(), null, UUID.randomUUID(), "SKU", name,
                BigDecimal.ONE, "NOS", new BigDecimal("1000"), new BigDecimal(rate), null, new BigDecimal("1000"),
                null, null, null, null, null);
    }
}
