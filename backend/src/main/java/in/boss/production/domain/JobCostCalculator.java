package in.boss.production.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Approved job-cost arithmetic.
 * Margin is {@code (selling price - actual cost) / selling price * 100}
 * when both amounts are positive. Selling price is never an input this class writes.
 */
public final class JobCostCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private JobCostCalculator() {}

    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal total(BigDecimal material, BigDecimal labour, BigDecimal overhead) {
        return money(material).add(money(labour)).add(money(overhead));
    }

    public static BigDecimal variance(BigDecimal actual, BigDecimal estimated) {
        return money(actual).subtract(money(estimated));
    }

    public static BigDecimal marginPct(BigDecimal sellingPrice, BigDecimal actualCost) {
        BigDecimal selling = money(sellingPrice);
        BigDecimal actual = money(actualCost);
        if (selling.signum() <= 0 || actual.signum() <= 0) {
            return null;
        }
        return selling.subtract(actual)
                .multiply(HUNDRED)
                .divide(selling, 4, RoundingMode.HALF_UP);
    }
}
