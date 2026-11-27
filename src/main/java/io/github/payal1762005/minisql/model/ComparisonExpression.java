package io.github.payal1762005.minisql.model;

import io.github.payal1762005.minisql.operators.ComparisonOperator;

import java.util.Objects;

/**
 * Compares a column with a literal value.
 */
public record ComparisonExpression(
        String columnName,
        ComparisonOperator operator,
        LiteralValue value) implements WhereExpression {
    public ComparisonExpression {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Comparison column must not be blank");
        }
        Objects.requireNonNull(operator, "Comparison operator must not be null");
        Objects.requireNonNull(value, "Comparison value must not be null");
    }
}
