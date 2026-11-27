package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.ComparisonSupport;
import io.github.payal1762005.minisql.model.Row;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Compares one row column with a typed expected value.
 *
 * <p>String expectations use lexicographical comparison. Number expectations
 * parse the row value as a decimal number, so CSV numeric text is compared
 * numerically rather than lexicographically.</p>
 */
public final class ComparisonPredicate implements Predicate<Row> {
    private final String columnName;
    private final ComparisonOperator operator;
    private final Object expectedValue;

    public ComparisonPredicate(String columnName, ComparisonOperator operator, Object expectedValue) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
        this.columnName = columnName;
        this.operator = Objects.requireNonNull(operator, "Comparison operator must not be null");
        if (expectedValue == null) {
            throw new IllegalArgumentException("Expected comparison value must not be null");
        }
        this.expectedValue = expectedValue;
        if (!(expectedValue instanceof String) && !(expectedValue instanceof Number)) {
            throw new IllegalArgumentException("Expected comparison value must be a String or Number");
        }
    }

    @Override
    public boolean test(Row row) {
        Objects.requireNonNull(row, "Row must not be null");
        if (!row.values().containsKey(columnName)) {
            throw new IllegalArgumentException("Column '" + columnName + "' is missing from row");
        }

        return ComparisonSupport.matches(columnName, operator, expectedValue,
                row.values().get(columnName));
    }
}
