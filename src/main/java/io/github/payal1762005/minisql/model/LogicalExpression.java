package io.github.payal1762005.minisql.model;

import java.util.Objects;

/**
 * Combines two WHERE expressions.
 */
public record LogicalExpression(
        LogicalOperator operator,
        WhereExpression left,
        WhereExpression right) implements WhereExpression {
    public LogicalExpression {
        Objects.requireNonNull(operator, "Logical operator must not be null");
        Objects.requireNonNull(left, "Left expression must not be null");
        Objects.requireNonNull(right, "Right expression must not be null");
    }
}
