package io.github.payal1762005.minisql.operators;

import java.util.Objects;

/**
 * Describes one aggregate expression and its output column name.
 *
 * <p>Use {@code "*"} as the input column for {@code COUNT(*)}. Other
 * functions require an input column.</p>
 */
public record AggregateSpecification(
        AggregateFunction function,
        String inputColumn,
        String outputColumn) {

    public AggregateSpecification {
        Objects.requireNonNull(function, "Aggregate function must not be null");
        if (inputColumn == null || inputColumn.isBlank()) {
            throw new IllegalArgumentException("Aggregate input column must not be blank");
        }
        if (outputColumn == null || outputColumn.isBlank()) {
            throw new IllegalArgumentException("Aggregate output column must not be blank");
        }
        if ("*".equals(inputColumn) && function != AggregateFunction.COUNT) {
            throw new IllegalArgumentException("Only COUNT supports '*' as its input column");
        }
    }

    public static AggregateSpecification countAll(String outputColumn) {
        return new AggregateSpecification(AggregateFunction.COUNT, "*", outputColumn);
    }
}
