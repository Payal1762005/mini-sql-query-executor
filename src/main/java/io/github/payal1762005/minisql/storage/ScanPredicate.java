package io.github.payal1762005.minisql.storage;

import io.github.payal1762005.minisql.model.ComparisonSupport;
import io.github.payal1762005.minisql.model.Row;
import io.github.payal1762005.minisql.operators.ComparisonOperator;

import java.util.Objects;

/**
 * A simple column-to-literal comparison that storage can evaluate while scanning.
 */
public record ScanPredicate(String columnName, ComparisonOperator operator, Object expectedValue) {
    public ScanPredicate {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
        Objects.requireNonNull(operator, "Comparison operator must not be null");
        if (expectedValue == null) {
            throw new IllegalArgumentException("Expected comparison value must not be null");
        }
        if (!(expectedValue instanceof String) && !(expectedValue instanceof Number)) {
            throw new IllegalArgumentException("Expected comparison value must be a String or Number");
        }
    }

    public boolean test(Row row) {
        Objects.requireNonNull(row, "Row must not be null");
        if (!row.values().containsKey(columnName)) {
            throw new IllegalArgumentException("Column '" + columnName + "' is missing from row");
        }
        return testValue(row.values().get(columnName));
    }

    public boolean testValue(String actualValue) {
        return ComparisonSupport.matches(columnName, operator, expectedValue, actualValue);
    }
}
