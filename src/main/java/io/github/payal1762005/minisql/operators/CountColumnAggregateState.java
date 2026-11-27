package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.Objects;

final class CountColumnAggregateState implements AggregateState {
    private final String column;
    private long count;

    CountColumnAggregateState(String column) {
        this.column = Objects.requireNonNull(column, "Aggregate input column must not be null");
    }

    @Override
    public void add(Row row) {
        Objects.requireNonNull(row, "Child operator returned a null row");
        if (!row.values().containsKey(column)) {
            throw new IllegalArgumentException(
                    "Aggregate input column '" + column + "' is missing from row");
        }
        if (row.values().get(column) != null) {
            count++;
        }
    }

    @Override
    public void merge(AggregateState other) {
        if (!(other instanceof CountColumnAggregateState state)
                || !column.equals(state.column)) {
            throw incompatibleState();
        }
        count += state.count;
    }

    @Override
    public String result() {
        return Long.toString(count);
    }

    private IllegalArgumentException incompatibleState() {
        return new IllegalArgumentException("Cannot merge incompatible aggregate states");
    }
}
