package in.boss.masterdata.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BomQuantityFormulaTest {

    @Test
    void evaluatesDimensionsWithStandardArithmeticPrecedence() {
        BigDecimal value = BomQuantityFormula.evaluate(
                "(length * width) / 1000000 + thickness",
                Map.of("length", new BigDecimal("1200"), "width", new BigDecimal("600"),
                        "thickness", new BigDecimal("0.02")));

        assertThat(value).isEqualByComparingTo("0.74");
    }

    @Test
    void rejectsUnsupportedSyntaxAndMissingValues() {
        assertThatThrownBy(() -> BomQuantityFormula.evaluate("length * width", Map.of("length", BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> BomQuantityFormula.validate("max(length, width)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BomQuantityFormula.validate("1 / 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
    }
}
