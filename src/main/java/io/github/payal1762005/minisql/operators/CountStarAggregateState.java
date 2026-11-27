package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.Objects;

final class CountStarAggregateState implements AggregateState {
    private long count;

    @Override
    public void add(Row row) {
        Objects.requireNonNull(row, "Child operator returned a null row");
        count++;
    }

    @Override
    public void merge(AggregateState other) {
        if (!(other instanceof CountStarAggregateState state)) {
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
