package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.math.BigDecimal;
import java.util.Objects;

abstract class AbstractNumericAggregateState implements AggregateState {
    private final String column;
    protected long count;

    AbstractNumericAggregateState(String column) {
        this.column = Objects.requireNonNull(column, "Aggregate input column must not be null");
    }

    @Override
    public final void add(Row row) {
        Objects.requireNonNull(row, "Child operator returned a null row");
        if (!row.values().containsKey(column)) {
            throw new IllegalArgumentException(
                    "Aggregate input column '" + column + "' is missing from row");
        }
        String value = row.values().get(column);
        if (value == null) {
            return;
        }
        addValue(parseNumber(value));
        count++;
    }

    @Override
    public final void merge(AggregateState other) {
        if (other == null || getClass() != other.getClass()) {
            throw incompatibleState();
        }
        AbstractNumericAggregateState state = (AbstractNumericAggregateState) other;
        if (!column.equals(state.column)) {
            throw incompatibleState();
        }
        mergeValues(state);
        count += state.count;
    }

    protected abstract void addValue(BigDecimal value);

    protected abstract void mergeValues(AbstractNumericAggregateState other);

    protected final String column() {
        return column;
    }

    protected final boolean isEmpty() {
        return count == 0;
    }

    private BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid numeric value '" + value
                    + "' in aggregate input column '" + column + "'", exception);
        }
    }

    private IllegalArgumentException incompatibleState() {
        return new IllegalArgumentException("Cannot merge incompatible aggregate states");
    }
}
