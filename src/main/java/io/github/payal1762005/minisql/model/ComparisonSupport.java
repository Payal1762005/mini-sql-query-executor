package io.github.payal1762005.minisql.model;

import io.github.payal1762005.minisql.operators.ComparisonOperator;

import java.math.BigDecimal;

/**
 * Shared value comparison semantics for row predicates and storage scans.
 */
public final class ComparisonSupport {
    private ComparisonSupport() {
    }

    public static boolean matches(
            String columnName, ComparisonOperator operator, Object expectedValue, String actualValue) {
        if (actualValue == null) {
            throw new IllegalArgumentException("Column '" + columnName + "' has a null value");
        }

        int comparison = expectedValue instanceof Number
                ? compareNumbers(actualValue, (Number) expectedValue, columnName)
                : actualValue.compareTo((String) expectedValue);
        return switch (operator) {
            case EQUALS -> comparison == 0;
            case NOT_EQUALS -> comparison != 0;
            case GREATER_THAN -> comparison > 0;
            case LESS_THAN -> comparison < 0;
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
        };
    }

    private static int compareNumbers(String actualValue, Number expected, String columnName) {
        BigDecimal actual = parseNumber(actualValue, "column '" + columnName + "'");
        BigDecimal expectedDecimal = parseNumber(expected.toString(), "expected value");
        return actual.compareTo(expectedDecimal);
    }

    private static BigDecimal parseNumber(String value, String description) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid numeric " + description + ": '" + value + "'",
                    exception);
        }
    }
}
