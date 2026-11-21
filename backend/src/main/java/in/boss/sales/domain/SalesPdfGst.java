package in.boss.sales.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class SalesPdfGst {
    private static final Pattern GSTIN = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("jammu and kashmir", "01"), Map.entry("himachal pradesh", "02"),
            Map.entry("punjab", "03"), Map.entry("chandigarh", "04"), Map.entry("uttarakhand", "05"),
            Map.entry("haryana", "06"), Map.entry("delhi", "07"), Map.entry("rajasthan", "08"),
            Map.entry("uttar pradesh", "09"), Map.entry("bihar", "10"), Map.entry("sikkim", "11"),
            Map.entry("arunachal pradesh", "12"), Map.entry("nagaland", "13"), Map.entry("manipur", "14"),
            Map.entry("mizoram", "15"), Map.entry("tripura", "16"), Map.entry("meghalaya", "17"),
            Map.entry("assam", "18"), Map.entry("west bengal", "19"), Map.entry("jharkhand", "20"),
            Map.entry("odisha", "21"), Map.entry("chhattisgarh", "22"), Map.entry("madhya pradesh", "23"),
            Map.entry("gujarat", "24"), Map.entry("dadra and nagar haveli and daman and diu", "26"),
            Map.entry("maharashtra", "27"), Map.entry("karnataka", "29"), Map.entry("goa", "30"),
            Map.entry("lakshadweep", "31"), Map.entry("kerala", "32"), Map.entry("tamil nadu", "33"),
            Map.entry("puducherry", "34"), Map.entry("andaman and nicobar islands", "35"),
            Map.entry("telangana", "36"), Map.entry("andhra pradesh", "37"), Map.entry("ladakh", "38"),
            Map.entry("new delhi", "07"), Map.entry("orissa", "21"), Map.entry("pondicherry", "34"),
            Map.entry("dadra and nagar haveli", "26"), Map.entry("daman and diu", "26"),
            Map.entry("hyderabad", "36"), Map.entry("bangalore", "29"), Map.entry("bengaluru", "29"),
            Map.entry("mumbai", "27"), Map.entry("pune", "27"), Map.entry("chennai", "33"),
            Map.entry("kolkata", "19"), Map.entry("ahmedabad", "24"));
    private static final Set<String> UTGST = Set.of("04", "26", "31", "35", "38");

    record Line(BigDecimal amount, BigDecimal rate) {}
    record Component(String name, BigDecimal rate, BigDecimal amount) {}
    record Totals(BigDecimal goods, BigDecimal discount, List<Component> tax, BigDecimal grand) {}

    private SalesPdfGst() {}

    static Totals calculate(List<Line> lines, BigDecimal discount, QuotationPdf.Party seller, QuotationPdf.Party buyer) {
        BigDecimal goods = BigDecimal.ZERO;
        Map<BigDecimal, BigDecimal> bases = new TreeMap<>();
        for (Line line : lines) {
            BigDecimal amount = zeroIfNull(line.amount());
            goods = goods.add(amount);
            if (line.rate() != null && line.rate().signum() > 0) {
                bases.merge(line.rate(), amount, BigDecimal::add);
            }
        }
        discount = zeroIfNull(discount);
        BigDecimal taxable = goods.subtract(discount).max(BigDecimal.ZERO);
        List<Component> tax = new ArrayList<>();
        BigDecimal grand = taxable;
        if (taxable.signum() > 0 && bases.values().stream().anyMatch(amount -> amount.signum() > 0)) {
            String sellerState = stateCode(seller, "seller");
            String buyerState = stateCode(buyer, "customer");
            boolean interstate = !sellerState.equals(buyerState);
            for (var entry : bases.entrySet()) {
                BigDecimal rate = entry.getKey();
                BigDecimal base = entry.getValue();
                if (base.signum() == 0) continue;
                BigDecimal taxBeforeDiscount = base.multiply(rate).movePointLeft(2);
                BigDecimal amount = taxBeforeDiscount.multiply(taxable).divide(goods, 2, RoundingMode.HALF_UP);
                if (interstate) {
                    tax.add(new Component("IGST", rate, amount));
                } else {
                    BigDecimal halfRate = rate.divide(BigDecimal.TWO);
                    // Settle an odd paise on the second component so the printed amounts reconcile.
                    BigDecimal cgst = amount.divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
                    tax.add(new Component("CGST", halfRate, cgst));
                    tax.add(new Component(UTGST.contains(sellerState) ? "UTGST" : "SGST", halfRate, amount.subtract(cgst)));
                }
                grand = grand.add(amount);
            }
        }
        return new Totals(goods, discount, tax, grand);
    }

    private static String stateCode(QuotationPdf.Party party, String role) {
        String gstin = normalize(party.gstin()).toUpperCase(Locale.ROOT);
        if (!gstin.isEmpty()) {
            if (GSTIN.matcher(gstin).matches() && STATES.containsValue(gstin.substring(0, 2))) {
                return gstin.substring(0, 2);
            }
            throw taxContextError("Correct the " + role + " GSTIN");
        }
        String state = normalize(party.taxAddressState()).replace("&", " and ").replaceAll("\\s+", " ");
        String code = STATES.get(state);
        if (code != null) return code;
        if (STATES.containsValue(state)) return state;
        throw taxContextError("Record the " + role + " GSTIN or a recognized address state");
    }

    private static ResponseStatusException taxContextError(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason + " to classify GST for this document");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
