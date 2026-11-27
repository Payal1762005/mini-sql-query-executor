package io.github.payal1762005.minisql.operators;

import io.github.payal1762005.minisql.model.Row;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Lazily returns only rows from its child that satisfy a predicate.
 */
public class FilterOperator implements Operator {
    private final Operator child;
    private final Predicate<Row> predicate;
    private Row nextRow;
    private boolean hasBufferedRow;
    private boolean closed;

    public FilterOperator(Operator child, Predicate<Row> predicate) {
        this.child = Objects.requireNonNull(child, "child operator must not be null");
        this.predicate = Objects.requireNonNull(predicate, "predicate must not be null");
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        if (hasBufferedRow) {
            return true;
        }

        try {
            while (child.hasNext()) {
                Row candidate = child.next();
                if (predicate.test(candidate)) {
                    nextRow = candidate;
                    hasBufferedRow = true;
                    return true;
                }
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
            throw new NoSuchElementException("No more rows satisfy the filter");
        }

        Row result = nextRow;
        nextRow = null;
        hasBufferedRow = false;
        return result;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            nextRow = null;
            hasBufferedRow = false;
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
