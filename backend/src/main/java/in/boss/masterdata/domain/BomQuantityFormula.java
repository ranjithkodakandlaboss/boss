package in.boss.masterdata.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class BomQuantityFormula {

    private static final int MAX_LENGTH = 256;

    private BomQuantityFormula() {}

    public static void validate(String formula) {
        parse(formula, null);
    }

    public static BigDecimal evaluate(String formula, Map<String, BigDecimal> specifications) {
        return parse(formula, specifications);
    }

    public static Set<String> variables(String formula) {
        Parser parser = new Parser(formula, null);
        parser.expression();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected token in quantity formula at position " + parser.position);
        }
        return Set.copyOf(parser.variables);
    }

    private static BigDecimal parse(String formula, Map<String, BigDecimal> specifications) {
        if (formula == null || formula.isBlank() || formula.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Quantity formula must contain 1 to 256 characters");
        }
        Parser parser = new Parser(formula, specifications);
        BigDecimal value = parser.expression();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected token in quantity formula at position " + parser.position);
        }
        return value;
    }

    private static final class Parser {
        private final String formula;
        private final Map<String, BigDecimal> specifications;
        private final Set<String> variables = new LinkedHashSet<>();
        private int position;

        private Parser(String formula, Map<String, BigDecimal> specifications) {
            this.formula = formula;
            this.specifications = specifications;
        }

        private BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                skipWhitespace();
                if (consume('+')) {
                    value = value.add(term());
                } else if (consume('-')) {
                    value = value.subtract(term());
                } else {
                    return value;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal value = factor();
            while (true) {
                skipWhitespace();
                if (consume('*')) {
                    value = value.multiply(factor());
                } else if (consume('/')) {
                    BigDecimal divisor = factor();
                    if (divisor.signum() == 0) {
                        throw new IllegalArgumentException("Division by zero in quantity formula");
                    }
                    value = value.divide(divisor, 12, RoundingMode.HALF_UP);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal factor() {
            skipWhitespace();
            if (consume('+')) {
                return factor();
            }
            if (consume('-')) {
                return factor().negate();
            }
            if (consume('(')) {
                BigDecimal value = expression();
                skipWhitespace();
                if (!consume(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis in quantity formula");
                }
                return value;
            }
            if (atEnd()) {
                throw new IllegalArgumentException("Expected a value in quantity formula");
            }

            char current = formula.charAt(position);
            if (Character.isDigit(current) || current == '.') {
                return number();
            }
            if (isIdentifierStart(current)) {
                return variable();
            }
            throw new IllegalArgumentException("Invalid character in quantity formula at position " + position);
        }

        private BigDecimal number() {
            int start = position;
            boolean decimalPoint = false;
            while (!atEnd()) {
                char current = formula.charAt(position);
                if (Character.isDigit(current)) {
                    position++;
                } else if (current == '.' && !decimalPoint) {
                    decimalPoint = true;
                    position++;
                } else {
                    break;
                }
            }
            try {
                return new BigDecimal(formula.substring(start, position));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid number in quantity formula", exception);
            }
        }

        private BigDecimal variable() {
            int start = position++;
            while (!atEnd()) {
                char current = formula.charAt(position);
                if (isIdentifierPart(current)) {
                    position++;
                } else {
                    break;
                }
            }
            String name = formula.substring(start, position);
            variables.add(name);
            if (specifications == null) {
                return BigDecimal.ONE;
            }
            BigDecimal value = specifications.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Specification value is required for '" + name + "'");
            }
            return value;
        }

        private boolean consume(char expected) {
            if (!atEnd() && formula.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(formula.charAt(position))) {
                position++;
            }
        }

        private boolean atEnd() {
            return position >= formula.length();
        }

        private static boolean isIdentifierStart(char value) {
            return value >= 'a' && value <= 'z';
        }

        private static boolean isIdentifierPart(char value) {
            return isIdentifierStart(value) || (value >= '0' && value <= '9') || value == '_';
        }
    }
}
