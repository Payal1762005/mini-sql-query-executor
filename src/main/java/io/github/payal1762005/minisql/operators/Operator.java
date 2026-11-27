package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.Iterator;

/**
 * A pull-based source of rows in a query execution pipeline.
 */
public interface Operator extends Iterator<Row>, AutoCloseable {
    @Override
    void close();
}
