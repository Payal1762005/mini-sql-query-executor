package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Lazily returns at most a fixed number of rows from its child.
 */
final class LimitOperator implements Operator {
    private final Operator child;
    private final int limit;
    private int emitted;
    private boolean childClosed;
    private boolean closed;

    LimitOperator(Operator child, int limit) {
        this.child = Objects.requireNonNull(child, "child operator must not be null");
        if (limit < 0) {
            throw new IllegalArgumentException("Limit must be non-negative");
        }
        this.limit = limit;
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        try {
            if (emitted >= limit) {
                close();
                return false;
            }
            if (child.hasNext()) {
                return true;
            }
            close();
            return false;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public Row next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No rows remain within the limit");
        }

        try {
            Row row = Objects.requireNonNull(child.next(), "Child operator returned a null row");
            emitted++;
            if (emitted >= limit) {
                close();
            }
            return row;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeChild();
    }

    private void closeChild() {
        if (!childClosed) {
            childClosed = true;
            child.close();
        }
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
