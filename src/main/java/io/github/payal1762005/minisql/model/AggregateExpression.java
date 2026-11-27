package io.github.payal1762005.minisql.model;

import io.github.payal1762005.minisql.operators.AggregateFunction;

import java.util.Objects;
import java.util.Optional;

/**
 * An aggregate call in a SELECT list.
 *
 * <p>The column name is {@code "*"} only for {@code COUNT(*)}.</p>
 */
public record AggregateExpression(
        AggregateFunction function, String columnName, Optional<String> alias) implements SelectItem {
    public AggregateExpression {
        Objects.requireNonNull(function, "Aggregate function must not be null");
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Aggregate column must not be blank");
        }
        Objects.requireNonNull(alias, "Aggregate alias optional must not be null");
        alias.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Aggregate alias must not be blank");
            }
        });
        if ("*".equals(columnName) && function != AggregateFunction.COUNT) {
            throw new IllegalArgumentException("Only COUNT supports '*' as its argument");
        }
    }

    public AggregateExpression(AggregateFunction function, String columnName) {
        this(function, columnName, Optional.empty());
    }

    public AggregateExpression(AggregateFunction function, String columnName, String alias) {
        this(function, columnName,
                Optional.of(Objects.requireNonNull(alias, "Aggregate alias must not be null")));
    }
}
