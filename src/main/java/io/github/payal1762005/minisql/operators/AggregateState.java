package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

/**
 * Mutable state for one aggregate expression, designed to be merged with a
 * compatible state produced from another set of rows.
 */
interface AggregateState {
    void add(Row row);

    void merge(AggregateState other);

    String result();
}
